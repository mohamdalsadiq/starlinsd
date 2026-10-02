# Slotra UI V2 Design References

هذه الملفات هي التصميمات المرجعية التي أنشأها Muse لتطبيق Slotra.

## الغرض

- مرجع UI/UX
- مرجع typography
- مرجع الألوان
- مرجع spacing
- مرجع cards
- مرجع navigation
- مرجع icons
- مرجع animations
- مرجع ترتيب العناصر
- مرجع تصميم الشاشات

## المحتويات

```
docs/design/slotra-ui-v2/
├── slotra-design-v2-all-screens.zip  # كل الشاشات في ملف واحد
├── README.md                          # هذا الملف
├── screens/                           # صور الشاشات منفصلة (15 شاشة)
│   ├── 01-home.png                    # الرئيسية
│   ├── 02-subscribers.png             # المشتركون
│   ├── 03-debts.png                   # الديون
│   ├── 04-shortcuts.png               # الاختصارات
│   ├── 05-daily-confirm.png           # التأكيد اليومي
│   ├── 06-devices.png                 # إدارة الأجهزة
│   ├── 07-more.png                    # المزيد
│   ├── 08-recovery.png                # استعادة الاشتراكات
│   ├── 09-plans.png                   # الباقات والأسعار
│   ├── 10-reports.png                 # التقارير
│   ├── 11-starlink-test.png           # اختبار Starlink
│   ├── 12-settings.png                # الإعدادات
│   ├── 13-new-subscription.png        # تسجيل اشتراك (حوار)
│   ├── 14-debt-payment.png            # تسجيل سداد دين (حوار)
│   └── 15-balance.png                 # تعديل دخل اليوم
└── assets/
    └── slotra-logo.jpg                # شعار التطبيق
```

## الهوية البصرية (v2)

- **الخلفيات**: `#0A0E1A`, `#0D1424` (كحلي داكن)
- **الأسطح**: `#111A2E`, `#0E1626`
- **الأساسي**: `#29B6F6` (أزرق كهربائي), `#4FC3F7`, `#0288D1`
- **النجاح**: `#69F0AE` (أخضر)
- **النص**: `#E8F1FF` (أساسي), `#5B7290` (ثانوي)

## مهم جدًا

هذه الملفات **DESIGN REFERENCES فقط**.

- لا يجوز تغيير business logic بناءً عليها.
- لا يجوز حذف أي feature موجودة في التطبيق أثناء تطبيق التصميم.
- التصميم يجب أن يكون presentation/UI layer فوق النظام الحالي.

### يجب الحفاظ على:

- subscriptions
- timers
- shortcuts
- notifications
- device tracking
- HOME
- WATCH
- recovery
- daily confirmation
- revenue
- debts
- cash
- Bankak
- invoices
- backup/restore
- permissions
