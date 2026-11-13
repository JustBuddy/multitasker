<img src="assets/icon.svg" width="64" height="64" alt="">

# MultiTasker

Pick up to three apps and get a home screen shortcut that opens them side by side. Built for the Galaxy Z Fold
(One UI 9 / Android 17).

## Modes

| | No setup | ADB grant ("true split") | ADB grant + accessibility service |
|---|---|---|---|
| 2 apps | Split, 50/50 | Split, left pane 28–72% | same |
| 3 apps, columns | 2-app split + pop-up | 50 / 25 / 25 | Any widths, each column at least 28% |
| 3 apps, one tall + two stacked | 2-app split + pop-up | Tall pane 28–72% wide, stacked pair 50/50 | Stacked pair 37–63% tall |

One UI never makes a split pane smaller than 250dp (656px on the Fold); narrower drags get clamped or hide the pane, so
the limits above keep every pane above that on the unfolded screen.

**True split** goes through One UI's internal split-screen commands (`WMShell splitscreen startIntents`,
`startSplitTasks`, `startIntentToCell`). Reaching them needs two permissions that can only be granted over ADB:

```sh
adb shell pm grant wtf.buddyworks.multitasker android.permission.DUMP
adb shell pm grant wtf.buddyworks.multitasker android.permission.PACKAGE_USAGE_STATS
```

The grant survives updates (`adb install -r`) but not an uninstall. If a One UI update removes the commands, shortcuts
fall back to the no-setup behavior.

**Three-pane sizes** have no command at all. One UI's dividers do accept drags to exact positions, so the optional
accessibility service "MultiTasker split sizes" drags them into place right after a shortcut opens. It only performs
gestures; it can't read screen content. Turn it on under Settings > Accessibility > Installed apps.

## Build

```sh
./gradlew :app:installDebug
```

## Created in

[T3 Code](https://t3.codes).
