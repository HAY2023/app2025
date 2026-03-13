@echo off
chcp 65001 >nul
echo ----------------------------------------------------
echo         جاري تجهيز المشروع ورفعه إلى GitHub
echo         لإنشاء نسخة APK في قسم الإصدارات (Releases)
echo ----------------------------------------------------
git add .
git commit -m "Fix CI: update Gradle setup and actions"
git tag -f v1.0.3
git push origin main --tags --force
echo ----------------------------------------------------
echo تم الرفع بنجاح! 
echo اذهب إلى حسابك في GitHub (مستودع app2025)
echo وانتظر 3 دقائق وسيتكون ملف الـ APK الخاص بالمسجد
echo جاهزاً للتحميل في قسم (Releases / الإصدارات).
echo ----------------------------------------------------
pause
