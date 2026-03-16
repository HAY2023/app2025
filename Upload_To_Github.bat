@echo off
echo ====================================================
echo    Uploading Masajid App to GitHub...
echo ====================================================
cd /d h:\app2025
echo [1/4] Adding files...
git add -A
echo [2/4] Committing...
git commit -m "Add Boot Splash Auto-Launcher, Audio Auto-Mute capabilities"
echo [3/4] Tagging v1.2.1...
git tag -f v1.2.1
echo [4/4] Pushing...
git push origin main --tags --force
echo ====================================================
echo    DONE! Check: https://github.com/HAY2023/app2025/releases
echo ====================================================
pause
