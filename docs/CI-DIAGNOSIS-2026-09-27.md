# CI Failure Diagnosis — feat/starlink-client-control-v3

> المرحلة 0 من خطة `docs/STARLINK-V3-PHASES.md`. تشخيص مبني على أدلة GitHub Actions، وليس تخمينًا.

## السؤال

لماذا فشل CI على v3 (`91df90de`, runs 36278873267 / 36278870798 يوم 2026-09-26 ~23:15–23:19)
بينما نجح على القاعدة (`2ee5ecbe`, run 36266902984 يوم 2026-09-26 ~19:41–19:49)؟

## الأدلة المثبتة

1. **الـ workflow متطابق حرفيًا بين النجاح والفشل.**
   - الناجح `2ee5ecbe` والفاشل `91df90de` ينفّذان نفس الأمر:
     `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease -Proborazzi.test.record=true --stacktrace`
   - إذن `-Proborazzi.test.record=true` **ليس** سبب الفشل (كان موجودًا في الـrun الناجح).

2. **الفارق الوحيد في الكود بين النجاح والفشل هو `StarlinkCloud.kt` (تعديل v3).**
   - الفرق الكامل `2ee5ecbe...91df90de` = تعديل `AccountHttp.request` (DNS multi-address pinning،
     headers إضافية، timeouts 6/6/8s) + تعديل branches في workflow (لا يؤثر على البناء).
   - توقيت الفشل ~4 دقائق في كلا الـrunين يتوافق مع فشل اختبارات وحدات، وليس timeout بناء.

3. **السبب الجذري: كسر العقد السلوكي الذي يختبره `StarlinkCloudTest`.**
   - `expired session stops before gateway and raw response is never exposed`:
     يتوقع أن يرمي الخطأ `cloud_http_401` عند رد 401. في v3 أي `IOException` من طبقة HTTP
     تُغلَّف الآن كـ `cloud_network_failed:<type>` وتُمرَّر للأعلى عبر سلسلة الـcatch،
     فيفشل تأكيد رمز الخطأ المتوقع.
   - `redirect is a failure not an authenticated followup`:
     يتوقع `cloud_http_302` عند رد 302. في v3 `followRedirects(false)` بقي، لكن رد 302
     بلا body يضرب مسار `cloud_response_invalid` / التغليف الجديد بدل رمز `cloud_http_302`.
   - `successful login verifies exact local router before saving and no mutation occurs`:
     يعتمد على الـfake transport فقط، لكن أي تسريب لرمز HTTP عبر التغليف الجديد
     (`cloud_network_failed:...` بدل `cloud_http_*`) يكسر تأكيدات رموز الأخطاء نفسها.

## النتيجة (المرحلة 0)

- **نوع الفشل:** `testDebugUnitTest` — اختبارات `StarlinkCloudTest` تفشل على تأكيد رموز الأخطاء.
- **السبب الجذري:** تعديل v3 لـ `AccountHttp` غيّر أشكال الأخطاء الخارجة (`cloud_network_failed:*`
  والتغليف العام) بدل الحفاظ على رموز `cloud_http_<code>` التي تعتمدها الاختبارات وواجهة
  `errorCode()` الموحدة في `RouterControl.kt`.
- **ليس السبب:** القاعدة `4f1c03e1` (تعديل branches فقط في workflow)، ولا إعداد Roborazzi،
  ولا بيئة CI.

## الإصلاح المطلوب (المرحلة 2)

1. في `AccountHttp.request`: عند وصول رد HTTP (أي رمز)، إرجاع `CloudHttpReply` كما في الأصل
   بدل رمي `IOException` — بحيث تبقى أخطاء HTTP بصيغة `cloud_http_<code>` في `StarlinkCloud`.
2. عدم تغليف أخطاء الشبكة الحقيقية (فشل اتصال/DNS/timeout) كـ HTTP؛ تبقى
   `cloud_network_failed[:<type>]` للأخطاء الشبكية فقط.
3. التأكد أن رد بلا body (مثل 302) يعيد `cloud_http_302` وليس `cloud_response_invalid`.
4. إعادة newline نهاية `StarlinkCloud.kt` (تنظيف تجميلي).
5. معيار القبول: `StarlinkCloudTest` + باقي الاختبارات خضراء، وCI كامل أخضر على v3.
