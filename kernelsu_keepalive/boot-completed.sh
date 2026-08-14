#!/system/bin/sh
# RustDesk Keepalive - KernelSU boot-completed script
# Runs as root after boot completes. Restores RustDesk controlled-side
# permissions so no manual re-grant is needed after reboot.
# Also starts a resident guardian loop that re-applies the permissions
# every 10 minutes — independent of the app process, so even if the
# system kills the app, accessibility and media projection stay enabled.

PACKAGE=com.carriez.flutter_hbb
SERVICE=$PACKAGE/com.carriez.flutter_hbb.InputService

restore_perms() {
  # 1. Re-enable the accessibility service, APPENDING to the existing list
  #    so other accessibility services are not clobbered.
  CURRENT=$(settings get secure enabled_accessibility_services 2>/dev/null)
  case ":$CURRENT:" in
    *":$SERVICE:"*) ;;
    *)
      if [ -z "$CURRENT" ] || [ "$CURRENT" = "null" ]; then
        settings put secure enabled_accessibility_services "$SERVICE"
      else
        settings put secure enabled_accessibility_services "$CURRENT:$SERVICE"
      fi
      ;;
  esac
  settings put secure accessibility_enabled 1

  # 2. Allow media projection without the confirmation dialog (Android 10+).
  appops set "$PACKAGE" PROJECT_MEDIA allow

  # 3. Make sure the controlled service is running. The action is
  #    REQUIRED: without it onStartCommand skips the media projection
  #    request block and screen capture stays un-granted.
  am start-service -n "$PACKAGE/com.carriez.flutter_hbb.MainService" \
    -a INIT_MEDIA_PROJECTION_AND_SERVICE --ez EXT_INIT_FROM_BOOT true >/dev/null 2>&1
}

restore_perms

# Resident guardian: re-apply permissions every 10 minutes, regardless of
# the app process state. Runs detached from this script's lifecycle.
(
  while true; do
    sleep 60
    restore_perms
  done
) &

# Trigger the app's own boot flow: BootReceiver starts MainService, which
# requests media projection; the PROJECT_MEDIA appop makes the confirmation
# dialog pass automatically. -f 0x20 also wakes the app if it is stopped.
am broadcast -f 0x20 -a com.carriez.flutter_hbb.DEBUG_BOOT_COMPLETED -p "$PACKAGE" >/dev/null 2>&1

exit 0
