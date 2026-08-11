// Local HTTP server on the controlling side that proxies gallery requests
// (media list / thumbnail / video byte ranges) over the rustdesk protocol
// to the controlled device. video_player on the controller plays
// http://127.0.0.1:PORT/video?path=... with Range support.

use hbb_common::message_proto::*;
use hbb_common::tokio::io::{AsyncReadExt, AsyncWriteExt};
use hbb_common::tokio::net::{TcpListener, TcpStream};
use hbb_common::tokio::sync::mpsc;
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::OnceLock;

pub type GallerySessionSender = Arc<dyn Fn(Message) + Send + Sync>;

static SESSION_SENDER: OnceLock<GallerySessionSender> = OnceLock::new();
static WAITERS: OnceLock<Mutex<HashMap<String, Vec<mpsc::UnboundedSender<Message>>>>> =
    OnceLock::new();

fn waiters() -> &'static Mutex<HashMap<String, Vec<mpsc::UnboundedSender<Message>>>> {
    WAITERS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn set_session_sender(f: GallerySessionSender) {
    let _ = SESSION_SENDER.set(f);
}

// Called from the controlling io_loop when a gallery response arrives.
pub fn on_gallery_data(key: &str, msg: Message) {
    let mut map = waiters().lock().unwrap();
    if let Some(senders) = map.remove(key) {
        for tx in senders {
            let _ = tx.send(msg.clone());
        }
    }
}

fn send_to_peer(msg: Message) {
    if let Some(f) = SESSION_SENDER.get() {
        f(msg);
    }
}

async fn wait_for(key: String, timeout_ms: u64) -> Option<Message> {
    let (tx, mut rx) = mpsc::unbounded_channel::<Message>();
    {
        let mut map = waiters().lock().unwrap();
        map.entry(key.clone()).or_default().push(tx);
    }
    match hbb_common::tokio::time::timeout(
        std::time::Duration::from_millis(timeout_ms),
        rx.recv(),
    )
    .await
    {
        Ok(Some(msg)) => {
            let _ = msg;
            Some(msg)
        }
        _ => {
            // remove stale waiter
            let mut map = waiters().lock().unwrap();
            if let Some(v) = map.get_mut(&key) {
                v.retain(|_| false);
                map.remove(&key);
            }
            None
        }
    }
}

fn parse_query(path: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    if let Some(q) = path.split('?').nth(1) {
        for pair in q.split('&') {
            if let Some((k, v)) = pair.split_once('=') {
                let v = url_decode(v);
                map.insert(k.to_string(), v);
            }
        }
    }
    map
}

fn url_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let h = (bytes[i + 1] as char).to_digit(16);
            let l = (bytes[i + 2] as char).to_digit(16);
            if let (Some(h), Some(l)) = (h, l) {
                out.push((h * 16 + l) as u8);
                i += 3;
                continue;
            }
        }
        if bytes[i] == b'+' {
            out.push(b' ');
        } else {
            out.push(bytes[i]);
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn http_response(stream: &mut TcpStream, status: &str, content_type: &str, body: &[u8], extra: &[(&str, &str)]) {
    let mut head = format!(
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n",
        status,
        content_type,
        body.len()
    );
    for (k, v) in extra {
        head.push_str(&format!("{}: {}\r\n", k, v));
    }
    head.push_str("\r\n");
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

async fn handle_connection(mut stream: TcpStream) {
    let mut buf = [0u8; 8192];
    let n = match stream.read(&mut buf).await {
        Ok(n) if n > 0 => n,
        _ => return,
    };
    let request = String::from_utf8_lossy(&buf[..n]).to_string();
    let mut lines = request.lines();
    let request_line = match lines.next() {
        Some(l) => l.to_string(),
        None => return,
    };
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("");
    if method != "GET" {
        http_response(&mut stream, "405 Method Not Allowed", "text/plain", b"", &[]);
        return;
    }
    let query = parse_query(path);
    let route = path.split('?').next().unwrap_or("").to_string();
    // Range header
    let mut range: Option<(u64, u64)> = None;
    for line in lines {
        if line.to_lowercase().starts_with("range:") {
            let v = line[6..].trim();
            if let Some(rest) = v.strip_prefix("bytes=") {
                if let Some((a, b)) = rest.split_once('-') {
                    if let Ok(a) = a.trim().parse::<u64>() {
                        let b = if b.trim().is_empty() {
                            a + 1024 * 1024 - 1
                        } else {
                            b.trim().parse::<u64>().unwrap_or(a)
                        };
                        range = Some((a, b));
                    }
                }
            }
        }
    }

    let result: Option<(String, String, Vec<u8>, Vec<(&'static str, String)>)> = match route.as_str() {
        "/list" => {
            let dir = query.get("dir").cloned().unwrap_or_default();
            let mut req = Misc::new();
            let mut r = MediaListRequest::new();
            r.set_dir(dir.clone());
            req.set_media_list_request(r);
            let mut msg_out = Message::new();
            msg_out.set_misc(req);
            send_to_peer(msg_out);
            let key = format!("list:{}", dir);
            match wait_for(key, 30_000).await {
                Some(m) => {
                    if let Some(d) = m.media_list_data.take() {
                        Some(("200 OK", "application/json".to_string(), d.json.into_bytes(), vec![]))
                    } else {
                        None
                    }
                }
                None => None,
            }
        }
        "/thumb" => {
            let path = query.get("path").cloned().unwrap_or_default();
            let mut req = Misc::new();
            let mut r = ThumbRequest::new();
            r.set_path(path.clone());
            req.set_thumb_request(r);
            let mut msg_out = Message::new();
            msg_out.set_misc(req);
            send_to_peer(msg_out);
            let key = format!("thumb:{}", path);
            match wait_for(key, 15_000).await {
                Some(m) => {
                    if let Some(d) = m.thumb_data.take() {
                        Some(("200 OK", "image/jpeg".to_string(), d.data, vec![]))
                    } else {
                        None
                    }
                }
                None => None,
            }
        }
        "/video" => {
            let path = query.get("path").cloned().unwrap_or_default();
            let (start, end) = range.unwrap_or((0, 1024 * 1024 - 1));
            let length = (end - start + 1).min(1024 * 1024) as u32;
            let mut req = Misc::new();
            let mut r = FileRangeRequest::new();
            r.set_path(path.clone());
            r.set_offset(start);
            r.set_length(length);
            req.set_file_range_request(r);
            let mut msg_out = Message::new();
            msg_out.set_misc(req);
            send_to_peer(msg_out);
            let key = format!("range:{}:{}", path, start);
            match wait_for(key, 20_000).await {
                Some(m) => {
                    if let Some(d) = m.file_range_data.take() {
                        let eof = d.eof;
                        let data = d.data;
                        let total = start + data.len() as u64;
                        let mut extra = vec![];
                        extra.push(("Content-Range", format!("bytes {}-{}/{}", start, total - 1, if eof { total.to_string() } else { "*".to_string() })));
                        if range.is_some() {
                            Some(("206 Partial Content", "video/mp4".to_string(), data, extra))
                        } else {
                            Some(("200 OK", "video/mp4".to_string(), data, extra))
                        }
                    } else {
                        None
                    }
                }
                None => None,
            }
        }
        _ => None,
    };

    match result {
        Some((status, ctype, body, extra)) => {
            let extra_refs: Vec<(&str, &str)> = extra
                .iter()
                .map(|(k, v)| (*k, v.as_str()))
                .collect();
            http_response(&mut stream, &status, &ctype, &body, &extra_refs);
        }
        None => {
            http_response(&mut stream, "404 Not Found", "text/plain", b"not found", &[]);
        }
    }
}

// Start the HTTP server on a random localhost port. Idempotent.
pub fn start() -> Option<u16> {
    static PORT: std::sync::OnceLock<u16> = std::sync::OnceLock::new();
    if let Some(p) = PORT.get() {
        return Some(*p);
    }
    let (tx, rx) = mpsc::channel(1);
    std::thread::spawn(move || {
        let rt = hbb_common::tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let listener = match TcpListener::bind("127.0.0.1:0").await {
                Ok(l) => l,
                Err(e) => {
                    log::error!("gallery http bind failed: {e}");
                    return;
                }
            };
            let port = match listener.local_addr() {
                Ok(a) => a.port(),
                Err(_) => return,
            };
            let _ = tx.send(port);
            loop {
                match listener.accept().await {
                    Ok((stream, _)) => {
                        hbb_common::tokio::spawn(async move {
                            handle_connection(stream).await;
                        });
                    }
                    Err(_) => break,
                }
            }
        });
    });
    PORT.set(rx.blocking_recv().ok()?).ok();
    PORT.get().copied()
}
