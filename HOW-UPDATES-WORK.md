# How updates reach the Android app

- **Game changes** (anything in `web/index.html`): raise the number in `web/version.txt` *and* `GAME_VERSION`
  in `web/index.html`, then push. Installed apps check `web/version.txt` when they open, download the new game in the
  background and switch to it (immediately if you're on the title screen, otherwise next launch). No reinstall needed.
- **Android wrapper changes** (anything in `android/` other than the game file): raise `SHELL_VERSION` in
  `MainActivity.java` and the number in `web/shell.json`, then push. The game shows a "new version of the app" banner
  that downloads the new APK from Releases.
- Every push also builds a fresh APK (with the latest game inside) on the Releases page.
