# Build MCS Android APK for free on GitHub Actions

**This guide requires a GitHub repository that you control.** There is no live APK attached to the source archive until a CI build actually succeeds.

1. Create a NEW GitHub repository named `mcs-android` (prefer Private during testing). This is NOT your Windows MCS repository.
2. Unzip `MCS_Android_Independent.zip` into its own folder.
3. Open PowerShell in the folder containing `app`, `.github`, `settings.gradle.kts`, and `README.md`.
4. Run these commands, replacing `<your-username>` with your GitHub username:

   ```powershell
   git init
   git add .
   git commit -m "Initial independent MCS Android source"
   git branch -M main
   git remote add origin "https://github.com/<your-username>/mcs-android.git"
   git push -u origin main
   ```

5. Open **GitHub → mcs-android → Actions → Build MCS Android debug APK**.
6. Click **Run workflow** if it did not run automatically after push.
7. **Only after the workflow succeeds** open its run page, then download the `MCS-Android-debug-apk` artifact.
8. Unzip the CI artifact to obtain `app-debug.apk`. Copy the APK to an Android 10+ phone and install it using the usual device confirmation.

The debug APK permits a clearly marked local testing session if no `license_public.key` is included. **Do not sell or distribute it as a licensed production release.**

For production, put only the existing Ed25519 public license key in `app/src/main/assets/license_public.key`, keep the private key on the admin PC, establish final Android signing, and issue an Android-specific license. Add the matching public Cloudflare revocation/check endpoint before advertising online revocation.

**Windows version remains untouched**: no commands here delete or modify `SyedShorts` or `D:\MCS_DIST`.
