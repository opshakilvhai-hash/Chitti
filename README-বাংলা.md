# Maria – ভয়েস অ্যাসিস্ট্যান্ট (Android Studio প্রজেক্ট)

## APK বানানোর ধাপ
1. কম্পিউটারে **Android Studio** ইনস্টল করুন (ফ্রি)।
2. File → Open → এই `Maria` ফোল্ডারটা খুলুন। Gradle Sync শেষ হওয়া পর্যন্ত অপেক্ষা করুন।
3. মেনু: **Build → Build Bundle(s)/APK(s) → Build APK(s)**।
4. APK পাওয়া যাবে `app/build/outputs/apk/debug/app-debug.apk` এ। ফোনে ইনস্টল করুন।
   (প্রথমবার Sync-এ কোনো error এলে Android Studio-র "Fix/Update" বাটন চাপুন।)

## প্রথমবার ফোনে
1. অ্যাপ খুলে ⚙ Settings → **Gemini API key** বসান (aistudio.google.com/apikey, ফ্রি) → সেভ।
2. "অনুমতি দিন" চাপুন: মাইক, কন্টাক্ট, SMS, কল, নোটিফিকেশন → Allow।
3. "অন্য অ্যাপের উপরে দেখানো" চালু করুন (না করলে ব্যাকগ্রাউন্ডে থাকা অবস্থায় অ্যাপ খোলা/কল করা আটকে যেতে পারে)।
4. ফোনের Settings → Apps → Maria → Battery → **Unrestricted** করুন, নইলে ফোন সার্ভিসটা বন্ধ করে দিতে পারে।
5. "🎧 সবসময় শোনো" চালু করুন। এরপর অ্যাপ বন্ধ থাকলেও "মারিয়া/Maria, …" বললে শুনবে।

## কী কী বলতে পারেন
- "Maria, রহিমকে মেসেজ করো আমি আসছি" → জিজ্ঞেস করবে → "হ্যাঁ" বললে SMS চলে যাবে।
- "Maria, কল দাও আম্মুকে", "টর্চ জ্বালাও", "৫ মিনিটের টাইমার", "ভোর ৬টায় অ্যালার্ম", "WhatsApp খোলো", "ভলিউম কমাও"।
- "Maria, আমার ভিডিওর জন্য SEO বানাও, বিষয়: …" → টাইটেল, বর্ণনা, ট্যাগ, থাম্বনেইল লেখা।
- "Maria, ভিডিও আপলোড করো" → ভিডিও বাছাইয়ের পর্দা আসবে → আপলোড হবে (ডিফল্ট Private)।
- "Maria, সর্বশেষ ভিডিওতে SEO বসাও"।

নিরাপত্তার জন্য মেসেজ/কল/আপলোডের আগে Maria জিজ্ঞেস করে। Settings-এ এটা বন্ধ করা যায়, তবে ভুল শোনা কথায় ভুল মেসেজ চলে যেতে পারে।

## YouTube সংযোগ (একবারের সেটআপ)
এটা Google-এর নিয়ম, এড়ানো যায় না:
1. console.cloud.google.com → নতুন Project।
2. **APIs & Services → Library → YouTube Data API v3 → Enable**।
3. **OAuth consent screen**: External, অ্যাপ নাম Maria, আপনার Gmail **Test users**-এ যোগ করুন।
   Scopes-এ `youtube.upload` ও `youtube` যোগ করুন।
4. **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `com.maria.assistant`
   - SHA-1: Android Studio-র Gradle প্যানেল → `Maria > app > Tasks > android > signingReport` চালিয়ে debug SHA-1 কপি করুন।
5. অ্যাপে YouTube (▶) → "Google দিয়ে লগইন"।

নোট: Google-এর নিয়মে যাচাই-না-হওয়া (unverified) API প্রজেক্ট থেকে আপলোড করা ভিডিও জোর করে **Private** হয়ে যেতে পারে। পাবলিক করতে হলে Google-এর API audit লাগে, নইলে YouTube Studio থেকে নিজে পাবলিক করুন।

## সীমাবদ্ধতা
- Android ফোন ঘুমিয়ে গেলে বা কোম্পানির ব্যাটারি-সেভার সার্ভিস মেরে ফেললে "সবসময় শোনা" থামতে পারে।
- Google-এর স্পিচ রিকগনিশন প্রতি কয়েক সেকেন্ডে রিস্টার্ট হয়; কিছু ফোনে হালকা বিপ শোনা যেতে পারে।
- WhatsApp-এ নিজে নিজে পাঠানো যায় না (শুধু মেসেজসহ চ্যাট খোলে)। Android এটা অ্যাপকে করতে দেয় না।
- এই কোড এখনো কম্পাইল করে পরীক্ষা করা হয়নি (আমার সার্ভারে Android SDK নেই)। Build-এ ছোটখাটো error এলে error-টা আমাকে পাঠান, ঠিক করে দেব।
