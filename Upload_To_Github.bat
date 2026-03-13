@echo off
chcp 65001 >nul
echo ----------------------------------------------------
echo         جاري تجهيز المشروع ورفعه إلى GitHub
echo         لإنشاء نسخة APK في قسم الإصدارات (Releases)
echo ----------------------------------------------------
git config --global init.defaultBranch main
git init
git add .
git commit -m "إطلاق الإصدار 1.0.0"
git tag -f v1.0.0
git branch -M main
git remote remove origin
git remote add origin https://github.com/HAY2023/app2025.git
git push -u origin main --tags --force
echo ----------------------------------------------------
echo تم الرفع بنجاح! 
echo اذهب إلى حسابك في GitHub (مستودع app2025)
echo وانتظر 3 دقائق وسيتكون ملف الـ APK الخاص بالمسجد
echo جاهزاً للتحميل في قسم (Releases / الإصدارات).
echo ----------------------------------------------------
pause
