# Pictorial Wallpaper Sync LSPosed

Tiny LSPosed module for ColorOS/HeyTap Pictorial.

It runs only in `com.heytap.pictorial`, watches the current lock-screen pictorial URI, and mirrors that image to the home wallpaper through Android `WallpaperManager`.

## Verified Device

- Package: `com.heytap.pictorial`
- Current pictorial setting key: `oplus_customize_keyguard_current_pictorial_uri`
- Verified on PFFM20 / OP520F with LSPosed v2.1.0.

## Build

```sh
./build.sh
```

Output:

```text
build/pictorial-sync-lsposed.apk
```

## Install

```sh
adb install -r -t build/pictorial-sync-lsposed.apk
```

Then enable the module in LSPosed Manager and scope it to:

```text
com.heytap.pictorial
```

Restart `com.heytap.pictorial` or reboot.

