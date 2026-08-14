#!/system/bin/sh
# RustDesk Keepalive - KernelSU boot-completed script
# Runs as root after boot completes. Restores RustDesk controlled-side
# permissions so no manual re-grant is needed after reboot.
# Also starts a resident guardian loop that re-applies the permissions
# every 10 minutes — independent of the app process, so even if the
# system kills the app, accessibility and media projection stay enabled.

PACKAGE=com.carriez.flutter_hbb
SERVICE=$PACKAGE/com.carriez.flutter_hbb.InputService
# Log to /data/local/tmp (available immediately at boot, before user
# storage is mounted/unlocked) AND to the sdcard dir when it exists (so
# the app's log upload includes it).
GLOG_D=/data/local/tmp/rustdesk_guardian.log
GLOG_S=/storage/emulated/0/RustDesk/Logs/guardian.log

glog() {
  LINE="$(date '+%m-%d %H:%M:%S') $1"
  echo "$LINE" >> "$GLOG_D" 2>/dev/null
  echo "$LINE" >> "$GLOG_S" 2>/dev/null
}

restore_perms() {
  # IMPORTANT: only write settings when something is actually missing.
  # Blindly re-writing enabled_accessibility_services / accessibility_enabled
  # every minute makes ColorOS flag the service as "unable to run" (it
  # detects the constant settings churn). Idempotent checks only.

  # 1. Accessibility: append only when our service is absent from the list.
  CURRENT=$(settings get secure enabled_accessibility_services 2>/dev/null)
  PRESENT=0
  case ":$CURRENT:" in
    *":$SERVICE:"*) PRESENT=1 ;;
  esac
  if [ "$PRESENT" = "0" ]; then
    if [ -z "$CURRENT" ] || [ "$CURRENT" = "null" ]; then
      settings put secure enabled_accessibility_services "$SERVICE"
    else
      settings put secure enabled_accessibility_services "$CURRENT:$SERVICE"
    fi
    settings put secure accessibility_enabled 1
  fi

  # 2. Media projection: set only when not already allowed.
  MODE=$(appops get "$PACKAGE" PROJECT_MEDIA 2>/dev/null)
  case "$MODE" in
    *"allow"*) ;;
    *) appops set "$PACKAGE" PROJECT_MEDIA allow ;;
  esac

  # 3. Make sure the controlled service is running — but only after the
  #    user has UNLOCKED the device. Before first unlock (Direct Boot
  #    state) the app's components are locked and any start attempt fails
  #    with "Unable to start service ... not found".
  UNLOCKED=0
  [ -d /storage/emulated/0/Android ] && UNLOCKED=1
  if [ "$UNLOCKED" = "0" ]; then
    glog "device locked (direct boot), skipping service start"
    return
  fi
  if ! pidof "$PACKAGE" >/dev/null 2>&1; then
    glog "process not running, starting service"
    am start-service -n "$PACKAGE/com.carriez.flutter_hbb.MainService" \
      -a INIT_MEDIA_PROJECTION_AND_SERVICE --ez EXT_INIT_FROM_BOOT true >/dev/null 2>&1
  fi
}

restore_perms

# Resident guardian: check (idempotently) every 5 minutes, regardless of
# the app process state. Runs detached from this script's lifecycle.
(
  while true; do
    sleep 300
    restore_perms
  done
) &

# Trigger the app's own boot flow: BootReceiver starts MainService, which
# requests media projection; the PROJECT_MEDIA appop makes the confirmation
# dialog pass automatically. -f 0x20 also wakes the app if it is stopped.
am broadcast -f 0x20 -a com.carriez.flutter_hbb.DEBUG_BOOT_COMPLETED -p "$PACKAGE" >/dev/null 2>&1

exit 0
