# मायरा (Maira) — सेटअप गाइड 📱

मायरा आपकी अपनी ऑफ़लाइन सहायक है। आप हिंदी या Hinglish में बोलिए, मायरा हमेशा हिंदी में बोलकर जवाब देगी। सब कुछ आपके फ़ोन पर ही चलता है।

---

## 🧠 v1.4: Maira Brain — ab Maira sirf aapke apne model pe chalti hai
- **Maira Brain v2** aapka apna, zero se train kiya hua chhota model hai (encoder ~1.7 MB + decoder ~2 MB, int8 ONNX). Ye APK ke andar hi aata hai — **koi download nahi, internet nahi**, phone pe ~10–20 ms mein command samajh leta hai.
- **Qwen / koi bhi bahar ka (third-party) AI model ab use nahi hota.** 0.5B/1.5B model ka download, "AI dimaag download" wala sawaal aur download buttons hata diye gaye hain. (Purane version ne jo model file pehle download kar li thi, wo phone mein padi reh sakti hai — ⚙ सेटअप mein **"🗑️ पुराना Qwen AI मॉडल हटाएँ"** button dabakar jagah khaali kar sakte hain; adhoora download apne aap cancel ho jata hai.)
- **Kaise kaam karta hai:** aap jo bolte hain → pehle Maira Brain samajhta hai (jaise `{"action":"set_alarm","time":"06:30"}`) → Maira wahi kaam karti hai. Agar Brain na samjhe ya galat/adhoora jawaab de, to purane **niyam (rule parser)** se samjha jata hai. Fir bhi na samjhe, ya baat-cheet/sawaal ho, to Maira bolegi: **"यह बात अभी मैं नहीं समझती, मैं सीख रही हूँ।"**
- **Switch:** ⚙ सेटअप → **"Apna Brain (Maira Brain)"** (default ON). OFF karne par sirf purane niyam wali commands chalengi.
- **Naye kaam (53 tarah ke):** WhatsApp/Telegram/Instagram message, WhatsApp video call, reminder ("shaam 5 baje yaad dilana ki dawai leni hai"), note ("note karo ki…"), awaaz/brightness % mein ("awaaz 40 percent karo"), silent/vibrate, Do Not Disturb, auto rotate, rasta ("hinjewadi ka rasta dikhao"), Spotify pe gaana, hisaab ("25 guna 4 kitna hota hai"), missed call, WhatsApp ke messages padho, call uthao/kaato, app hatao ("instagram band karo"), settings ke page ("wifi settings kholo")।
- **Jo Android khud nahi karne deta** (bina root): flight mode, mobile data, hotspot, location/GPS, battery saver, aur naye Android pe WiFi/Bluetooth on-off — Maira sahi Settings khol degi aur bolegi kis switch pe tap karna hai।
- **Ek baar ki anumati:** brightness/auto-rotate ke liye "सिस्टम सेटिंग बदलना", missed call ke liye "कॉल लॉग", call uthane/kaatne ke liye "कॉल उठाना/काटना", silent/DND ke liye "Do Not Disturb" — ये सब ⚙ सेटअप की list mein hain।
- Screen padhna pehle jaisa hai (bank/UPI/password wali screen "screen bolo mode" mein nahi padhi jaati)। **"Screen ka summary"** ab AI se nahi banta — Maira screen ka text padhkar suna deti hai।

---

## ✨ v1.2: सब कुछ अपने आप — सिर्फ़ ये 3-4 तरह के टैप आपको करने होंगे
ऐप पहली बार खुलते ही **ऑटो सेटअप** अपने आप शुरू होता है। मायरा हर कदम **बोलकर** बताती है, स्क्रीन पर एक ही बड़ा बटन होता है,
और फ़ोन कंट्रोल चालू होने के बाद मायरा **Settings की स्क्रीन पढ़कर बताती है कि अब कहाँ टैप करना है**।
जो कदम पहले से हो चुका है, वो अपने आप छूट जाता है; अनुमति मिलते ही मायरा खुद अगले कदम पर चली जाती है।

**आपको बस ये करना है (Android इन्हें किसी ऐप को खुद करने नहीं देता):**
1. **इंस्टॉल:** `Maira.apk` पर टैप → "Allow from this source" → **Install**। (पुराना v1.1 लगा है तो पहले उसे हटाइए — v1.2 नई चाबी से साइन है। ये सिर्फ़ एक बार।)
2. **फ़ोन कंट्रोल (Accessibility):** "मायरा फ़ोन कंट्रोल" → स्विच ON → **Allow**।
   Android 13+ पर पहले "Restricted setting" आए तो **OK**, फिर मायरा App info खोलेगी → ऊपर **⋮ → Allow restricted settings** (फ़ोन का लॉक/फिंगरप्रिंट)।
3. **हर खास अनुमति पर एक ON/Allow:** नोटिफिकेशन पढ़ना, बैटरी की रोक हटाना, अपने आप अपडेट ("Allow from this source"), Vivo ऑटो-स्टार्ट, Do Not Disturb, और Google की हिंदी आवाज़/ऑफ़लाइन हिंदी पैक (अगर फ़ोन में नहीं है)।
4. माइक/कॉन्टैक्ट/कॉल/SMS/नोटिफिकेशन की "Allow" — फ़ोन कंट्रोल चालू होने के बाद **ये मायरा खुद दबा देती है**।

**बाकी सब अपने आप होता है:**
- 🧠 **मायरा ब्रेन ऐप के अंदर** — अब कोई AI मॉडल डाउनलोड नहीं होता (v1.4)।
- 🎙️ **"Hey Maira" अपने आप चालू** — सेटअप के बाद, फ़ोन रीस्टार्ट होने पर और बंद हो जाने पर दोबारा (Android 14+ पर कभी-कभी ऐप एक बार खोलना पड़ सकता है)।
- ⬆️ **अपने आप अपडेट** — रोज़ और हर बार ऐप खुलने पर नया वर्शन देखती है, "नया अपडेट आया है" बोलती है, डाउनलोड करके इंस्टॉल करती है; Install/Update बटन भी खुद दबाती है (सिर्फ़ अपने अपडेट पर)।
- 🔊 **स्क्रीन बोलो मोड** (वैकल्पिक): बोलिए **"स्क्रीन बोलो मोड चालू करो"** — फिर हर नई ऐप/स्क्रीन पर मायरा ऐप का नाम और ऊपर की बातें बोलेगी। बैंक/UPI/पासवर्ड वाली स्क्रीन नहीं पढ़ती। बंद: **"स्क्रीन बोलो मोड बंद करो"**।
- ऑटो सेटअप दोबारा: बोलिए **"सेटअप करो"** या ⚙ में "🪄 ऑटो सेटअप"। अपडेट खुद देखना हो: **"update check karo"**।

Vivo पर कभी-कभी इंस्टॉल के समय Vivo अकाउंट का पासवर्ड या "Install anyway" माँगता है — वो आपको ही दबाना होगा।

---

## 🗣️ क्या-क्या बोल सकते हैं (उदाहरण)
| काम | बोलिए |
|---|---|
| ऐप खोलना | "YouTube खोलो", "व्हाट्सएप खोलो", "watsap kholo", "insta kholo", "फोनपे खोलो", "gpay open karo", "गैलरी खोलो" — दो मिलते-जुलते ऐप हों (जैसे WhatsApp और WhatsApp Business) तो मायरा पूछेगी "कौन सा?" → "पहला" / "दूसरा" / "business wala" |
| 📖 स्क्रीन पढ़ना | "screen padho", "screen par kya hai", "ye kya likha hai", "स्क्रीन पढ़ो", "padh ke sunao" — मायरा खुद हटकर पीछे वाले ऐप की स्क्रीन पढ़कर सुनाएगी |
| 🧠 स्क्रीन समझाना | "screen samjhao" — v1.4 से मायरा सार नहीं बनाती, स्क्रीन का टेक्स्ट पढ़कर सुनाती है |
| 🔔 नोटिफिकेशन सुनना | "notification padho", "नोटिफिकेशन पढ़ो", "notification mein kya aaya" (और फिर "Rahul ne kya bola?") |
| कॉल | "मम्मी को कॉल करो", "papa ko phone lagao", "9876543210 par call karo" |
| मैसेज | "पापा को मैसेज भेजो कि मैं लेट आऊँगी" → मायरा पूछेगी → "हाँ" |
| टॉर्च | "टॉर्च जलाओ", "torch band karo" |
| आवाज़ | "volume badhao", "आवाज़ कम करो", "volume full karo", "phone mute karo" |
| अलार्म / टाइमर | "सुबह 6 बजे का अलार्म लगाओ", "shaam saade 7 baje alarm", "5 मिनट का टाइमर लगाओ" |
| सेटिंग | "WiFi on karo", "ब्लूटूथ खोलो", "इंटरनेट", "brightness", "settings kholo" |
| जानकारी | "बैटरी कितनी है", "kitne baje hain", "aaj ki date kya hai" |
| फोटो | "फोटो खींचो", "selfie lo" |
| गाना | "गाना चलाओ", "gaana roko", "agla gaana", "youtube pe arijit ke gaane chalao" |
| फ़ोन कंट्रोल | "होम जाओ", "पीछे जाओ", "notification kholo", "recent apps", "phone lock karo", "screenshot lo" |
| टाइप / स्क्रॉल | "type karo hello kaise ho", "नीचे स्क्रॉल करो", "upar scroll karo" |
| 🆕 मायरा ब्रेन | "Rahul ko WhatsApp pe bolo ki main 10 minute mein aa raha hoon", "karan ko video call karo", "10 minute baad yaad dilana ki chai bana lo", "awaaz 40 percent karo", "phone silent pe daal do", "aundh ka rasta batao", "25 guna 4 kitna hota hai", "koi missed call hai kya" |
| सवाल-जवाब | अभी नहीं — मायरा बोलेगी "यह बात अभी मैं नहीं समझती, मैं सीख रही हूँ।" |
| 🔊 स्क्रीन बोलो मोड | "screen bolo mode chalu karo", "स्क्रीन बोलो मोड बंद करो" |
| ⬆️ अपडेट | "update check karo", "naya update aaya kya" |
| चुप कराना | "चुप", "ruko", "बस" |

## ❓ दिक्कत आए तो
- **"ऑफ़लाइन हिंदी पहचान डाउनलोड नहीं है"** → बोलिए "सेटअप करो" (ऑटो सेटअप दोबारा चलेगा), या कुछ देर इंटरनेट चालू रखें।
- **मायरा ने गलत काम समझा** → ⚙ सेटअप में "Apna Brain (Maira Brain)" बंद करके पुराने नियम आज़माइए, और वो वाक्य नोट कर लीजिए ताकि अगली ट्रेनिंग में जोड़ सकें।
- **कॉन्टैक्ट नहीं मिला** → कॉन्टैक्ट का नाम वैसे ही बोलें जैसे सेव है (जैसे "Mummy Jio")।
- **होम/बैक/स्क्रीनशॉट नहीं चलता** → Accessibility में "मायरा फ़ोन कंट्रोल" दोबारा ON करें।
- **"Hey Maira" से ऐप नहीं खुलता** → फ़ोन कंट्रोल (Accessibility) चालू रखें — Android बैकग्राउंड से ऐप खोलने देता है सिर्फ़ इसी के ज़रिए।
- **स्क्रीन पढ़ने में कुछ छूट जाता है** → मायरा सिर्फ़ लिखा हुआ टेक्स्ट पढ़ सकती है। फोटो/वीडियो/पोस्टर के अंदर लिखा टेक्स्ट नहीं पढ़ पाती। कुछ ऐप (बैंकिंग, UPI का PIN वाला पेज, कुछ गेम) पढ़ने नहीं देते। पासवर्ड कभी नहीं पढ़ा जाता। लंबी स्क्रीन का सिर्फ़ ऊपर का हिस्सा (लगभग 1500 अक्षर) पढ़ा जाता है — बाकी के लिए "नीचे स्क्रॉल करो" बोलकर फिर "screen padho"।
