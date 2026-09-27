# خطة تنفيذ Starlink Client Control v3 — مراحل تفصيلية

> هذا الملف هو المرجع الوحيد للتنفيذ. كل مرحلة مكتوبة كبرومت مستقل:
> أي وكيل يقرأ المرحلة يفهم الهدف والسياق والخطوات ومعايير القبول بدون مرجع خارجي.
>
> **قواعد عامة ملزمة لكل المراحل:**
> - المستودع: `mohamdalsadiq/starlinsd` — الفرع الأساسي `feat/subscription-shortcuts` — فرع العمل `feat/starlink-client-control-v3`.
> - ممنوع: merge إلى `main`، force push، حذف فروع، reset/rebase تدميري.
> - LAN gRPC = قراءة فقط (جرّبنا الكتابة → status 7 / PERMISSION_DENIED). الكتابة فقط عبر Authenticated Starlink Cloud. ممنوع LAN write fallback.
> - النجاح لا يُقاس بـ HTTP 200 ولا dispatched ولا إرسال الطلب. النجاح فقط:
>   Pause: DISPATCHED → ACCEPTED → CONFIG_APPLIED → READBACK_VERIFIED → INTERNET_BLOCKED
>   Unpause: DISPATCHED → CONFIG_APPLIED → READBACK_VERIFIED → INTERNET_RESTORED
>   وإذا تعذّر إثبات آخر خطوة تُعرض الحالة UNKNOWN ولا يُقال "نجح".
> - `clientId` هو المعرّف الأساسي. MAC وIP معلومات مساعدة فقط.
> - جهاز التحكم (Samsung A23 الذي يشغّل Slotra) ممنوع حظره نهائيًا؛ الحماية في طبقة التحكم وليست UI فقط؛ عند استهدافه: ABORT قبل أي mutation.
> - قبل أي mutation: قراءة الإعدادات الحالية كاملة، تعديل target clientId فقط، الحفاظ على كل clientConfigs والجداول والحقول الأخرى byte-for-byte.
> - لا تخزن passwords / Wi-Fi passwords / session cookies / secrets في logs أو الـjournal.
> - Recovery بعد إعادة تشغيل التطبيق = fresh read من الراوتر، وليس stale snapshot.
> - لا Phase 2 (Temporary Access / Unknown Device Protection) قبل نجاح اختبار Pause/Unpause الحقيقي.
> - مراجع البروتوكول: Dishylink (LOCAL-API.md, routerClientUpdate, routerConfigUpdate, starlinkCloudHandler, resilientFetch) و Eitol/starlink-client. الفهم ثم التطبيق على معمارية Slotra، وليس نسخ كود أعمى.

---

## المرحلة 0 — تشخيص فشل CI (تشخيص فقط، بدون تعديل كود)

**الهدف:** معرفة السبب الحقيقي لفشل GitHub Actions على فرع v3 قبل أي إصلاح.

**السياق المعروف:**
- آخر run ناجح على القاعدة كان عند commit `2ee5ecbe`.
- الفرع v3 عند `91df90de` فشل في run `36278873267` (pull_request) و`36278870798` (push) يوم 2026-09-26 ~23:15–23:19.
- workflow الحالي ينفّذ:
  `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease -Proborazzi.test.record=true --stacktrace`
- الاشتباه الأولي: `-Proborazzi.test.record=true` يكسر الـbuild عند نقص الصور المرجعية (خصوصًا مع `StarlinkControlUiTest.kt`) — لكن هذا اشتباه غير مثبت.

**الخطوات:**
1. تحميل logs الكاملة لـ run `36278873267` (zip من GitHub Actions → Artifacts/logs، أو `gh run view 36278873267 --log`).
2. تحديد أول مهمة Gradle فاشلة: هل هي `testDebugUnitTest` أم `lintDebug` أم `assembleDebug` أم `assembleRelease`؟
3. استخراج نص الخطأ الفعلي (compile error / test failure / lint error / missing reference images).
4. تحميل artifact `verification-reports` إن وُجد وقراءة تقارير الاختبار وlint.
5. كتابة النتيجة في تعليق على PR #15: المهمة الفاشلة + نص الخطأ + السبب الجذري.

**معايير القبول:**
- سبب جذري واحد مثبت بنص الخطأ (ليس تخمينًا).
- تمييز واضح: هل الفشل من تعديلات v3 أم موجود أيضًا على القاعدة؟

**مخرجات المرحلة:** تقرير سبب الفشل — لا يُسمح بأي تعديل كود قبل إتمامها.

---

## المرحلة 1 — التحقق من سلامة القاعدة `4f1c03e1`

**الهدف:** التأكد أن أحدث commit على `feat/subscription-shortcuts` (وهو `4f1c03e1`) يبني بنجاح قبل دمجه في v3.

**السياق المعروف:**
- `4f1c03e1` دُفع مباشرة للقاعدة يوم 2026-09-26 22:53 و**لم يمر على CI إطلاقًا**.
- عدّل 4 ملفات خارج نطاق Starlink: `MainViewModel.kt`, `SubscriptionRepository.kt`, `SubscriptionAlarms.kt`, `HomePanels.kt` (منطق اشتراكات/إيرادات).

**الخطوات:**
1. تشغيل workflow `Android Build` يدويًا (workflow_dispatch) على `feat/subscription-shortcuts` — أو عمل PR مؤقت يستهدف القاعدة لإجبار CI (بدون دمج).
2. إن نجح CI: القاعدة سليمة ويمكن دمجها في v3.
3. إن فشل CI: توثيق أن المشكلة من القاعدة نفسها وليست من v3، وإصلاحها في فرع fix منفصل من القاعدة (بعد موافقة المالك)، ثم إعادة التحقق.

**معايير القبول:** CI أخضر مثبت على `4f1c03e1`، أو إثبات موثق أن الفشل من القاعدة مع خطة إصلاحها.

---

## المرحلة 2 — إصلاح سبب فشل CI على v3

**الهدف:** build أخضر على v3 بعد معرفة السبب من المرحلة 0.

**الخطوات (تُختار حسب نتيجة المرحلة 0):**
- لو السبب Roborazzi record mode: إزالة `-Proborazzi.test.record=true` من أمر الـbuild العادي (يُستخدم فقط عند تحديث الصور مقصودًا)، أو إضافة الصور المرجعية الناقصة.
- لو السبب lint: إصلاح مخالفة lint في الملف المتسبب (تعديل محدود، بدون refactor).
- لو السبب اختبار وحدة: فهم الاختبار الفاشل أولًا، وإصلاح الكود أو الاختبار بما يحافظ على سلوك الأمان (ممنوع حذف اختبار أمان لإسكات الفشل).
- لو السبب compile: إصلاح خطأ الترجمة مباشرة.
- إصلاح ثانوي مقبول أثناء اللمس: استرجاع newline نهاية `StarlinkCloud.kt`.

**القيود:** تعديل محدود يعالج سببًا واحدًا؛ لا تغيير لسلوك Starlink الوظيفي في هذه المرحلة؛ لا تعديل على `main`.

**معايير القبول:** run `Android Build` أخضر على آخر commit في v3 (testDebugUnitTest + lintDebug + assembleDebug + assembleRelease كلها ناجحة).

---

## المرحلة 3 — دمج القاعدة في v3

**الهدف:** جلب commit القاعدة الناقص `4f1c03e1` إلى v3 بدون إعادة كتابة تاريخ.

**الخطوات:**
1. التأكد من نجاح المرحلة 1 (القاعدة سليمة) والمرحلة 2 (v3 أخضر).
2. دمج `feat/subscription-shortcuts` في `feat/starlink-client-control-v3` عبر merge commit عادي (ممنوع rebase/force push).
3. حل أي تعارض يدويًا بمراجعة كل ملف؛ ملفات الاشتراكات الأربعة لا تتقاطع مع ملفات Starlink فلا يتوقع تعارض — لكن يُتحقق ولا يُفترض.
4. التأكد بعد الدمج أن `StarlinkCloud.kt` ما زال يحمل تحسينات v3 (DNS pinning، headers، timeouts) وأن endpoint ما زال `https://starlink.com/api/SpaceX.API.Device.Device/Handle`.
5. تشغيل CI على الـmerge commit والتأكد أنه أخضر.

**معايير القبول:** v3 محدّث بالكامل على القاعدة (behind = 0) مع CI أخضر.

---

## المرحلة 4 — تقوية حماية جهاز التحكم

**الهدف:** جعل منع حظر Samsung A23 غير قابل للفشل حتى لو تغيّر IP الجهاز.

**السياق المعروف (نقطة ضعف موجودة):**
- الحماية الحالية تقارن IP الهدف بـ `localIps` فقط. لو تغيّر IP التحكم (DHCP) قد لا يطابق وقد يُحظر جهاز التحكم خطأً.
- `AuthenticatedRouterLink` يقرأ STATUS محليًا قبل كل عملية ويعرف routerId.

**الخطوات:**
1. إضافة طبقة تحقق ثانية داخل `RouterControl`/`checkTarget` (وليس UI): قبل أي mutation، التأكد من هوية جهاز التحكم من جهة الراوتر (مطابقة clientId/MAC الخاص بجهاز التحكم كما يراه الراوتر، مع رفض MAC المُقنّع `XX:XX:XX` كدليل).
2. إذا تعذّر إثبات أن الهدف ليس جهاز التحكم بشكل قاطع → ABORT بدون إرسال أي mutation (fail-closed وليس fail-open).
3. إضافة اختبار وحدة: (أ) هدف يطابق IP جهاز التحكم → مرفوض. (ب) هوية جهاز التحكم غير قابلة للإثبات → مرفوض. (ج) هدف آخر مؤكد → مسموح.

**القيود:** التغيير في طبقة `network` فقط؛ لا تغيير في الواجهات العامة للـUI؛ اختبارات جديدة قبل أو مع التعديل.

**معايير القبول:** الاختبارات الثلاثة خضراء + CI كامل أخضر + توثيق آلية الحماية في `docs/STARLINK-CLOUD-TEST.md`.

---

## المرحلة 5 — تقرير التنفيذ الشامل في PR #15

**الهدف:** توثيق ما تم فعليًا كما طلب المالك في الخطة الأصلية.

**المحتوى المطلوب في body الـPR:**
- الفرع الجديد و HEAD/base commit النهائيان.
- الملفات المعدّلة وسبب كل تعديل.
- الملفات التي لم تُعدّل ولماذا (ملفات الاشتراكات الأربعة — خارج نطاق Starlink، نُقلت بدمج القاعدة فقط).
- طريقة المصادقة: WebView login → cookies `Starlink.Com.Sso` + `Starlink.Com.Access.V1` → refresh عبر `https://api.starlink.com/auth-rp/auth/user` → تخزين AES-GCM في noBackupFilesDir بمفتاح Android Keystore.
- الـcloud endpoint: `https://starlink.com/api/SpaceX.API.Device.Device/Handle` (gRPC-Web) مع Router target field 13.
- الـmutation: Request 3001 → wifi_config field 1 → client_configs field 74 + apply_client_configs field 1089=true → جدولة `_permanent` بالمدى 0..10080. الحذف عند Unpause = إزالة `_permanent` فقط.
- نتائج: unit tests / lint / build / CI (أرقام الـruns وروابطها).
- ما الذي أصبح جاهزًا، وما الذي يحتاج الاختبار الحقيقي على Starlink.
- تذييل واضح: لم يتم merge إلى main.

**معايير القبول:** PR body كامل بالعناصر أعلاه، بدون ادعاء نجاح لم يُثبت (INTERNET_BLOCKED يظل "يحتاج اختبار عتاد").

---

## المرحلة 6 — الاختبار الحقيقي على العتاد (يدوي مع المالك)

**الهدف:** إثبات أن Slotra يحظر جهازًا فعليًا ويعيده — المعيار النهائي الوحيد للنجاح.

**الأجهزة:** Samsung A23 (Slotra/التحكم) + هاتف آخر (target) + راوتر Starlink (Wi-Fi رئيسي، بدون VPN، بدون راوتر إضافي).

**الخطوات (قصيرة، بالترتيب، وكل فشل يوقف السلسلة):**
1. Discovery: فتح اختبار Starlink في Slotra → قراءة قائمة الأجهزة ومطابقتها مع تطبيق Starlink الرسمي.
2. Authentication: ربط الحساب عبر WebView → Verify link → نجاح حفظ الجلسة.
3. Self-device protection: محاولة اختيار A23 كهدف → يجب ABORT فورًا بدون أي mutation.
4. Pause: اختيار الهاتف الآخر → تأكيد → انتظار قراءة readback.
5. إثبات الحظر: تعطيل بيانات الجوال على الهاتف الهدف → تجربة فتح موقع جديد → يجب أن يفشل (INTERNET_BLOCKED). إن لم ينقطع: الحالة UNKNOWN وليست نجاحًا.
6. Unpause: Check restoration → تأكيد.
7. إثبات الإعادة: تجربة الإنترنت على الهدف → يجب أن تعود (INTERNET_RESTORED).
8. نسخ التشخيصات المُنقّحة (sanitized diagnostics) وإرفاقها بالـPR.

**معايير القبول:** نجاح الخطوات 1–7 فعليًا على العتاد. عندها فقط تُعتبر الميزة "مُثبتة"، ويُفتح النقاش في Phase 2.

---

## حالة التنفيذ (تُحدّث مع التقدم)

| المرحلة | الحالة | ملاحظات |
|---|---|---|
| 0 — تشخيص فشل CI | قيد التنفيذ | logs run 36278873267 |
| 1 — سلامة القاعدة | لم تبدأ | تعتمد على 0 |
| 2 — إصلاح CI | لم تبدأ | تعتمد على 0 |
| 3 — دمج القاعدة | لم تبدأ | تعتمد على 1+2 |
| 4 — حماية جهاز التحكم | لم تبدأ | تعتمد على 3 |
| 5 — تقرير PR | لم تبدأ | تعتمد على 4 |
| 6 — اختبار العتاد | لم تبدأ | يدوي مع المالك |
