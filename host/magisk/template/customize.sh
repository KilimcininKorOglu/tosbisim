# SIM Relay host module installer hook (sourced by Magisk, APatch or KernelSU). GPL-3.0-or-later.
# APatch and KernelSU leave mounting a module's system folder to a separately installed "metamodule" in their recent
# versions (their daemon names the feature): without one the app this module carries never appears, and nothing says
# why (APatch 11282, 4 Nov 2026). Stop here and say what to do instead.
if [ "$APATCH" = true ] || [ "$KSU" = true ]; then
  RM=APatch; D=/data/adb/apd; [ "$KSU" = true ] && { RM=KernelSU; D=/data/adb/ksud; }
  if grep -q metamodule "$D" 2>/dev/null && ! grep -qs '^metamodule=1' /data/adb/modules/*/module.prop /data/adb/modules_update/*/module.prop; then
    ui_print "! $RM mounts a module's files only through a mount"
    ui_print "! metamodule, and none is installed: HermesSIM's app"
    ui_print "! would not appear."
    abort "! Install one first (e.g. Magic Mount or Hybrid Mount), restart, then install HermesSIM again."
  fi
fi
# A new install means a host is wanted again: the mark "Stop using this phone as a host" left behind (the app's
# Retire.givenBack) and its boot script go (a tester reinstalled after a removal, 4 Nov 2026). Here, and once more by
# service.sh at the first boot: a restart right after the install lost this delete on the APatch emulator.
touch "$MODPATH/installed-afresh"
if [ "$BOOTMODE" = true ]; then
  settings delete global tosbisim_given_back >/dev/null 2>&1
  rm -f /data/adb/service.d/tosbisim-given-back.sh
fi
# The daemon for this phone's Android (Magisk's ARCH, from ro.product.cpu.abi): 64-bit, or 32-bit (cheap phones, old
# ones on custom ROMs). The module carries both; only this one stays.
case "$ARCH" in
  arm64) ABI=arm64-v8a ;;
  arm) ABI=armeabi-v7a ;;
  *) abort "! unsupported processor: $ARCH (HermesSIM needs an ARM phone)" ;;
esac
mv -f "$MODPATH/bin/$ABI/simrelayd" "$MODPATH/simrelayd" || abort "! the module has no daemon for $ABI"
rm -rf "$MODPATH/bin"
set_perm "$MODPATH/simrelayd" 0 0 0755
# Installed from inside the app (one APK for everyone): the module carries no APK, so take the installed one.
PRIV="$MODPATH/system/priv-app/SimRelayHost"
if [ ! -f "$PRIV/SimRelayHost.apk" ]; then
  APK=$(pm path dev.simrelay.host 2>/dev/null | head -1 | sed 's/^package://')
  [ -f "$APK" ] || abort "! Install the HermesSIM Host app first, then install this module from inside it"
  mkdir -p "$PRIV" && cp "$APK" "$PRIV/SimRelayHost.apk" || abort "! could not copy the app"
  set_perm_recursive "$PRIV" 0 0 0755 0644
  ui_print "- using the installed HermesSIM Host app ($APK)"
fi
ui_print "- HermesSIM Host is installed."
ui_print "- Restart the phone: HermesSIM starts by itself and opens"
ui_print "  its screen, ready to pair with your phone."
# Android keeps a parsed copy of system apps in /data/system/package_cache and, for files on the system
# partition, only invalidates it on an OTA (mtime is ignored). Drop ours so an updated APK is re-read.
rm -f /data/system/package_cache/*/SimRelayHost-*
