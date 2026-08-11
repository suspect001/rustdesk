import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_hbb/common.dart';
import 'package:video_player/video_player.dart';

class GalleryPage extends StatefulWidget {
  final String id;
  final int port;

  const GalleryPage({super.key, required this.id, required this.port});

  @override
  State<GalleryPage> createState() => _GalleryPageState();
}

class _GalleryItem {
  final String path;
  final String name;
  final String type;
  final int size;
  final int mtime;
  _GalleryItem(this.path, this.name, this.type, this.size, this.mtime);
}

class _GalleryPageState extends State<GalleryPage> {
  final _tabs = ['All', 'DCIM', 'Pictures', 'Screenshots', 'Download'];
  int _tab = 0;
  List<_GalleryItem> _items = [];
  bool _loading = false;
  String _error = '';

  String get _base => 'http://127.0.0.1:${widget.port}';

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
    });
    try {
      final dir = _tabs[_tab];
      final client = HttpClient();
      final req = await client
          .getUrl(Uri.parse('$_base/list?dir=${Uri.encodeComponent(dir)}'));
      final resp = await req.close();
      final body = await resp.transform(utf8.decoder).join();
      client.close();
      if (resp.statusCode == 200) {
        final arr = jsonDecode(body) as List;
        setState(() {
          _items = arr
              .map((e) => _GalleryItem(
                  e['path'] as String,
                  e['name'] as String,
                  e['type'] as String,
                  (e['num'] as num?)?.toInt() ?? 0,
                  (e['mtime'] as num?)?.toInt() ?? 0))
              .toList();
        });
      } else {
        setState(() => _error = 'HTTP ${resp.statusCode}');
      }
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(translate('Gallery')),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(48),
          child: SizedBox(
            height: 48,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                for (var i = 0; i < _tabs.length; i++)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: ChoiceChip(
                      label: Text(_tabs[i]),
                      selected: _tab == i,
                      onSelected: (_) {
                        setState(() => _tab = i);
                        _load();
                      },
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error.isNotEmpty
              ? Center(child: Text(_error))
              : _items.isEmpty
                  ? Center(child: Text(translate('No media files')))
                  : GridView.builder(
                      padding: const EdgeInsets.all(4),
                      gridDelegate:
                          const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 4,
                              mainAxisSpacing: 4,
                              crossAxisSpacing: 4),
                      itemCount: _items.length,
                      itemBuilder: (context, i) {
                        final item = _items[i];
                        return GestureDetector(
                          onTap: () {
                            if (item.type == 'video') {
                              Navigator.push(
                                context,
                                MaterialPageRoute(
                                    builder: (_) => GalleryVideoPage(
                                        port: widget.port,
                                        path: item.path,
                                        name: item.name)),
                              );
                            } else {
                              Navigator.push(
                                context,
                                MaterialPageRoute(
                                    builder: (_) => GalleryImagePage(
                                        port: widget.port,
                                        path: item.path,
                                        name: item.name)),
                              );
                            }
                          },
                          child: Stack(
                            fit: StackFit.expand,
                            children: [
                              Image.network(
                                '$_base/thumb?path=${Uri.encodeComponent(item.path)}',
                                fit: BoxFit.cover,
                                errorBuilder: (_, __, ___) =>
                                    Container(color: Colors.grey.shade800),
                                loadingBuilder: (_, child, progress) =>
                                    progress == null
                                        ? child
                                        : Container(color: Colors.grey.shade900),
                              ),
                              if (item.type == 'video')
                                const Center(
                                  child: Icon(Icons.play_circle_outline,
                                      color: Colors.white70, size: 32),
                                ),
                            ],
                          ),
                        );
                      },
                    ),
    );
  }
}

class GalleryImagePage extends StatelessWidget {
  final int port;
  final String path;
  final String name;
  const GalleryImagePage(
      {super.key, required this.port, required this.path, required this.name});

  @override
  Widget build(BuildContext context) {
    final base = 'http://127.0.0.1:$port';
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(title: Text(name), backgroundColor: Colors.black),
      body: Center(
        child: InteractiveViewer(
          maxScale: 5,
          child: Image.network(
            'http://127.0.0.1:$port/image?path=${Uri.encodeComponent(path)}',
            fit: BoxFit.contain,
            loadingBuilder: (_, child, progress) => progress == null
                ? child
                : const CircularProgressIndicator(color: Colors.white54),
            errorBuilder: (_, __, ___) => const Text('load failed'),
          ),
        ),
      ),
    );
  }
}

class GalleryVideoPage extends StatefulWidget {
  final int port;
  final String path;
  final String name;
  const GalleryVideoPage(
      {super.key, required this.port, required this.path, required this.name});

  @override
  State<GalleryVideoPage> createState() => _GalleryVideoPageState();
}

class _GalleryVideoPageState extends State<GalleryVideoPage> {
  VideoPlayerController? _controller;
  String _error = '';

  @override
  void initState() {
    super.initState();
    final base = 'http://127.0.0.1:${widget.port}';
    _controller = VideoPlayerController.networkUrl(
        Uri.parse('$base/video?path=${Uri.encodeComponent(widget.path)}'));
    _controller!.initialize().then((_) {
      if (mounted) setState(() {});
      _controller!.play();
    }).catchError((e) {
      if (mounted) setState(() => _error = '播放失败: $e');
    });
  }

  @override
  void dispose() {
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(title: Text(widget.name), backgroundColor: Colors.black),
      body: Center(
        child: _error.isNotEmpty
            ? Text(_error, style: const TextStyle(color: Colors.white70))
            : _controller == null || !_controller!.value.isInitialized
                ? const CircularProgressIndicator(color: Colors.white54)
                : AspectRatio(
                    aspectRatio: _controller!.value.aspectRatio,
                    child: Stack(alignment: Alignment.bottomCenter, children: [
                      VideoPlayer(_controller!),
                      _VideoControls(controller: _controller!),
                    ]),
                  ),
      ),
    );
  }
}

class _VideoControls extends StatefulWidget {
  final VideoPlayerController controller;
  const _VideoControls({required this.controller});

  @override
  State<_VideoControls> createState() => _VideoControlsState();
}

class _VideoControlsState extends State<_VideoControls> {
  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: widget.controller,
      builder: (context, value, child) {
        if (!value.isInitialized) return const SizedBox.shrink();
        return Container(
          color: Colors.black54,
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Row(children: [
            IconButton(
              icon: Icon(value.isPlaying
                  ? Icons.pause
                  : Icons.play_arrow),
              color: Colors.white,
              onPressed: () {
                value.isPlaying
                    ? widget.controller.pause()
                    : widget.controller.play();
              },
            ),
            Expanded(
              child: VideoProgressIndicator(widget.controller,
                  allowScrubbing: true, colors: const VideoProgressColors(
                      playedColor: Colors.red, backgroundColor: Colors.white24)),
            ),
          ]),
        );
      },
    );
  }
}
