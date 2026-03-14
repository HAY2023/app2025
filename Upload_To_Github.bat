@echo off
echo ====================================================
echo    Uploading MasjidTV App to GitHub...
echo    رفع التطبيق إلى GitHub (الإصدار 1.0.9)
echo ====================================================
cd /d h:\app2025

echo [1] Adding files...
git add -A

echo [2] Committing changes...
git commit -m "Patch v1.0.9"

echo [3] Tagging release v1.0.9...
git tag -f v1.0.9

echo [4] Pushing to GitHub...
git push origin main --tags --force

echo ====================================================
echo    DONE! Check GitHub Releases in 3 minutes.
echo    تم الانتهاء! تحقق من صفحة الإصدارات بعد 3 دقائق:
echo    https://github.com/HAY2023/app2025/releases
echo ====================================================
pause
