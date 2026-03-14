@echo off
echo ====================================================
echo    Uploading MasjidTV App to GitHub...
echo ====================================================
cd /d h:\app2025
git add -A
git commit -m "Fix build: add missing resources and rename app"
git tag -f v1.0.6
git push origin main --tags --force
echo ====================================================
echo    DONE! Check GitHub Releases in 3 minutes.
echo    https://github.com/HAY2023/app2025/releases
echo ====================================================
pause
