@echo off
echo ====================================================
echo    Uploading Masajid TV App (Luxury Islamic v1.0.10)
echo ====================================================
cd /d h:\app2025

echo [1/4] Adding files...
git add -A

echo [2/4] Committing...
git commit -m "Release v1.0.10: Fix foreground service and permissions"

echo [3/4] Tagging v1.0.11...
git tag -f v1.0.11

echo [4/4] Pushing to GitHub...
git push origin main --tags --force

echo ====================================================
echo    SUCCESS! Version v1.0.10 is live.
echo    Check: https://github.com/HAY2023/app2025/releases
echo ====================================================
pause
