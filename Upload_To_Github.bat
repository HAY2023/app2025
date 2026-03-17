@echo off
echo ====================================================
echo    Uploading Masajid TV App (Luxury Islamic v1.3.0)
echo ====================================================
cd /d h:\app2025

echo [1/4] Adding files...
git add -A

echo [2/4] Committing...
git commit -m "Final Release v1.3.0: Fixed missing imports in AlarmScheduler and added Luxury Islamic Theme"

echo [3/4] Tagging v1.3.0...
git tag -f v1.3.0

echo [4/4] Pushing to GitHub...
git push origin main --tags --force

echo ====================================================
echo    SUCCESS! Version v1.3.0 is live.
echo    Check: https://github.com/HAY2023/app2025/releases
echo ====================================================
pause
