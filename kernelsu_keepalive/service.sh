#!/system/bin/sh
# RustDesk Keepalive - KernelSU service.sh (late_start service stage).
# Runs BEFORE boot-completed: set PROJECT_MEDIA here so the media
# projection dialog auto-passes even if the app's BOOT_COMPLETED receiver
# fires first (the boot ordering race is then impossible).

PACKAGE=com.carriez.flutter_hbb
SERVICE=$PACKAGE/com.carriez.flutter_hbb.InputService

# 1. Allow media projection without the confirmation dialog (Android 10+).
appops set "$PACKAGE" PROJECT_MEDIA allow 2>/dev/null

# 2. Re-enable the accessibility service (append-style, no clobber).
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

exit 0
