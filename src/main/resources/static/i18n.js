// Interface language: English or Bangla.
//
// The English text is the key. An element or a string with no Bangla entry shows its
// English, so a screen that has not been translated yet is never blank and never
// shows a key like "nav.home". That is also why partial coverage is honest rather
// than broken: the person sees a mixed screen, not an empty one.
//
// The choice is a per-viewer convenience and carries nothing secret, so it lives in
// localStorage. The access token must never go there, and does not.
//
// What this does not translate: messages written by the server (validation and
// error text), and the content people author themselves. Those stay as written.

const BN = {
  // Sidebar groups
  'Personal': 'ব্যক্তিগত',
  'This organization': 'এই প্রতিষ্ঠান',
  'Tests': 'পরীক্ষা',
  'Learning': 'শেখা',
  'Hiring': 'নিয়োগ',
  'My work': 'আমার কাজ',
  'Admin': 'অ্যাডমিন',
  // Sidebar items and page titles
  'Home': 'হোম',
  'Practice': 'অনুশীলন',
  'My organizations': 'আমার প্রতিষ্ঠান',
  'Overview': 'সারসংক্ষেপ',
  'Create tests': 'পরীক্ষা তৈরি',
  'Members': 'সদস্য',
  'Courses': 'কোর্স',
  'My batches': 'আমার ব্যাচ',
  'Messages': 'বার্তা',
  'Certificates': 'সনদ',
  'My tests': 'আমার পরীক্ষা',
  'Billing': 'বিলিং',
  'Platform': 'প্ল্যাটফর্ম',
  'Profile & settings': 'প্রোফাইল ও সেটিংস',
  'Support': 'সহায়তা',
  'Sign out': 'সাইন আউট',
  'Menu': 'মেনু',
  // Page subtitles
  'Your learning and work in one place': 'আপনার শেখা আর কাজ এক জায়গায়',
  'Learn at your own pace': 'নিজের গতিতে শিখুন',
  'Join or create another organization': 'আরেকটি প্রতিষ্ঠানে যোগ দিন বা নতুন খুলুন',
  'Talk with your instructor or learners': 'শিক্ষক বা শিক্ষার্থীদের সাথে কথা বলুন',
  'Your tests, attempts and results': 'আপনার পরীক্ষা, চেষ্টা আর ফলাফল',
  'Your issued certificates': 'আপনার পাওয়া সনদ',
  'Your identity, sign-in and plan': 'আপনার পরিচয়, সাইন-ইন আর প্ল্যান',
  'Get help from the JachAI support team': 'JachAI সহায়তা দলের কাছ থেকে সাহায্য নিন',
  // Roles and people
  'Your role here:': 'এখানে আপনার ভূমিকা:',
  'Owner': 'মালিক',
  'Admin': 'অ্যাডমিন',
  'Instructor': 'শিক্ষক',
  'Learner': 'শিক্ষার্থী',
  'Recruiter': 'নিয়োগকারী',
  'Candidate': 'প্রার্থী',
  'Reviewer': 'পর্যালোচক',
  'Academy': 'একাডেমি',
  'Talent': 'ট্যালেন্ট',
  'Campaigns': 'ক্যাম্পেইন',
  // Home
  'Hello': 'হ্যালো',
  'Welcome': 'স্বাগতম',
  'Personal account': 'ব্যক্তিগত অ্যাকাউন্ট',
  'You can practice right away. A join code or an invitation connects you to an organization.':
    'আপনি এখনই অনুশীলন করতে পারেন। জয়েন কোড বা আমন্ত্রণ পেলে কোনো প্রতিষ্ঠানে যুক্ত হতে পারবেন।',
  'Here is your work, and what to do next.': 'এই যে আপনার কাজ, আর এরপর কী করবেন।',
  'Here is what needs attention in this organization.': 'এই প্রতিষ্ঠানে এখন যেগুলোর দিকে নজর দেওয়া দরকার।',
  'Join another organization': 'আরেকটি প্রতিষ্ঠানে যোগ দিন',
  'Start another organization': 'আরেকটি প্রতিষ্ঠান খুলুন',
  'Profile, password and plan': 'প্রোফাইল, পাসওয়ার্ড আর প্ল্যান',
  // Messages
  'Today': 'আজ',
  'Yesterday': 'গতকাল',
  'Started': 'শুরু',
  'No conversations yet. Choose someone below and press Start.':
    'এখনো কোনো কথোপকথন নেই। নিচ থেকে কাউকে বেছে নিয়ে "শুরু করুন" চাপুন।',
  'Chat with your instructor or learners.': 'আপনার শিক্ষক বা শিক্ষার্থীদের সাথে কথা বলুন।',
  'People in this organization': 'এই প্রতিষ্ঠানের সদস্যরা',
  // Home: the next-step card
  'Recommended next': 'পরবর্তী ধাপ',
  'Start with a skill you want to strengthen': 'যে দক্ষতাটা আরও ভালো করতে চান, সেটা দিয়ে শুরু করুন',
  'Choose a topic or try a starter question. Personal practice never changes an official test result.':
    'একটি বিষয় বেছে নিন বা একটি নমুনা প্রশ্ন করে দেখুন। ব্যক্তিগত অনুশীলন কখনো আপনার আনুষ্ঠানিক পরীক্ষার ফল বদলায় না।',
  'Revisit {topic}': '{topic} আবার দেখুন',
  'A recent answer needs review. Try another question on this topic and check the explanation.':
    'সাম্প্রতিক একটি উত্তর আবার দেখা দরকার। এই বিষয়ে আরেকটি প্রশ্ন করে ব্যাখ্যাটা পড়ুন।',
  'Practice this topic': 'এই বিষয়টি অনুশীলন করুন',
  'Check your assigned tests': 'আপনার জন্য নির্ধারিত পরীক্ষাগুলো দেখুন',
  'You have 1 test waiting. Open it to check when it is available.':
    'আপনার ১টি পরীক্ষা অপেক্ষায় আছে। কখন দেওয়া যাবে দেখতে খুলুন।',
  'You have {n} tests waiting. Open them to check when they are available.':
    'আপনার {n}টি পরীক্ষা অপেক্ষায় আছে। কখন দেওয়া যাবে দেখতে খুলুন।',
  // Home: facts
  '{q} AI questions and {a} answers left today': 'আজ আরও {q}টি এআই প্রশ্ন আর {a}টি উত্তর বাকি',
  'Practice allowance unavailable right now': 'অনুশীলনের সীমা এখন দেখানো যাচ্ছে না',
  'You have not joined an organization yet': 'আপনি এখনো কোনো প্রতিষ্ঠানে যোগ দেননি',
  '1 organization': '১টি প্রতিষ্ঠান',
  '{n} organizations': '{n}টি প্রতিষ্ঠান',
  '1 recent practice answer': 'সাম্প্রতিক ১টি অনুশীলন-উত্তর',
  '{n} recent practice answers': 'সাম্প্রতিক {n}টি অনুশীলন-উত্তর',
  'No practice answers yet': 'এখনো কোনো অনুশীলন-উত্তর নেই',
  '{n} to review': 'পর্যালোচনার অপেক্ষায় {n}টি',
  // Home: your work
  'Assignments in {org}. Personal practice is separate.': '{org}-এ আপনাকে দেওয়া পরীক্ষা। ব্যক্তিগত অনুশীলন আলাদা।',
  'Assignments could not be loaded. Open My tests to retry.': 'পরীক্ষার তালিকা আনা যায়নি। আবার চেষ্টা করতে "আমার পরীক্ষা" খুলুন।',
  'Upcoming': 'আসছে',
  'Closed': 'শেষ',
  'Available': 'চালু আছে',
  'Opens {when}': 'শুরু {when}',
  'Closes {when}': 'শেষ হবে {when}',
  'Closed {when}': 'শেষ হয়েছে {when}',
  '{n} min': '{n} মিনিট',
  'Open to see the instructions and when it is available': 'নির্দেশনা আর কখন দেওয়া যাবে দেখতে খুলুন',
  'Assigned test': 'নির্ধারিত পরীক্ষা',
  'Open test': 'পরীক্ষা খুলুন',
  'No assignments in this workspace yet. An organizer can invite you; personal practice is ready now.':
    'এই প্রতিষ্ঠানে এখনো আপনাকে কোনো পরীক্ষা দেওয়া হয়নি। আয়োজক আপনাকে আমন্ত্রণ জানাতে পারেন; ব্যক্তিগত অনুশীলন এখনই শুরু করা যায়।',
  'Your private practice is ready. You do not need an organization.':
    'আপনার ব্যক্তিগত অনুশীলন প্রস্তুত। এর জন্য কোনো প্রতিষ্ঠান লাগবে না।',
  'Continue personal practice': 'ব্যক্তিগত অনুশীলন চালিয়ে যান',
  '1 question available to this account': 'এই অ্যাকাউন্টে ১টি প্রশ্ন আছে',
  '{n} questions available to this account': 'এই অ্যাকাউন্টে {n}টি প্রশ্ন আছে',
  'Ready': 'প্রস্তুত',
  'Start personal practice': 'ব্যক্তিগত অনুশীলন শুরু করুন',
  'Choose a topic or try a starter question.': 'একটি বিষয় বেছে নিন বা একটি নমুনা প্রশ্ন করে দেখুন।',
  'Practice history could not be loaded. Open Practice to retry.': 'অনুশীলনের ইতিহাস আনা যায়নি। আবার চেষ্টা করতে "অনুশীলন" খুলুন।',
  // Home: static text
  'AI Tutor': 'এআই টিউটর',
  'Get help understanding a result once it has been released. Not available during an exam.':
    'ফল প্রকাশের পর সেটা বুঝতে সাহায্য নিন। পরীক্ষা চলাকালীন পাওয়া যায় না।',
  'Your own progress': 'আপনার নিজের অগ্রগতি',
  'Pick a topic, create a question, and learn from the explanation.': 'একটি বিষয় বেছে প্রশ্ন বানান, আর ব্যাখ্যা পড়ে শিখুন।',
  'Your invitation': 'আপনার আমন্ত্রণ',
  'Use an invitation or a join code from your organizer.': 'আয়োজকের দেওয়া আমন্ত্রণ বা জয়েন কোড ব্যবহার করুন।',
  'Your team': 'আপনার দল',
  'Start an Academy to teach, or Talent to hire.': 'শেখানোর জন্য একাডেমি, অথবা নিয়োগের জন্য ট্যালেন্ট খুলুন।',
  // Home: first steps for someone with no organization yet
  'Join an organization or start your own': 'কোনো প্রতিষ্ঠানে যোগ দিন বা নিজের প্রতিষ্ঠান খুলুন',
  'What would you like to do?': 'আপনি কী করতে চান?',
  'Tell us who you are and we will show the right steps. This does not change what you can access.':
    'আপনি কে সেটা বললে আমরা সঠিক ধাপগুলো দেখাব। এতে আপনি কী কী ব্যবহার করতে পারবেন তা বদলায় না।',
  'I am here as': 'আমি এখানে',
  'Choose one': 'একটি বেছে নিন',
  'Student or test taker': 'শিক্ষার্থী বা পরীক্ষার্থী',
  'Teacher or instructor': 'শিক্ষক বা প্রশিক্ষক',
  'Recruiter or hiring team member': 'নিয়োগকারী বা নিয়োগ দলের সদস্য',
  'Starting an Academy or a company': 'একাডেমি বা কোম্পানি খুলছি',
  'Choose one above. You can change it any time.': 'উপর থেকে একটি বেছে নিন। যেকোনো সময় বদলাতে পারবেন।',
  'Open Practice now. To take a test from an Academy or a company, use the code or invitation they gave you.':
    'এখনই অনুশীলন শুরু করুন। কোনো একাডেমি বা কোম্পানির পরীক্ষা দিতে হলে তাদের দেওয়া কোড বা আমন্ত্রণ ব্যবহার করুন।',
  'Join an Academy when you are invited. If you start a new Academy, you become its owner and can invite other instructors.':
    'আমন্ত্রণ পেলে কোনো একাডেমিতে যোগ দিন। নতুন একাডেমি খুললে আপনি তার মালিক হবেন এবং অন্য শিক্ষকদের ডাকতে পারবেন।',
  'Join a company when you are invited. If you start a new one, you become its owner and can invite recruiters.':
    'আমন্ত্রণ পেলে কোনো কোম্পানিতে যোগ দিন। নতুন একটি খুললে আপনি তার মালিক হবেন এবং নিয়োগকারীদের ডাকতে পারবেন।',
  'Start an Academy to teach and give certificates, or a Talent organization to hire.':
    'শেখাতে ও সনদ দিতে একাডেমি খুলুন, অথবা নিয়োগের জন্য ট্যালেন্ট প্রতিষ্ঠান খুলুন।',
  'Join with a code': 'কোড দিয়ে যোগ দিন',
  'You can practice right now': 'আপনি এখনই অনুশীলন করতে পারেন',
  'You can use Practice without any organization. If an organizer invites you, open their link and sign in with the same email. The organization then appears in your sidebar.':
    'কোনো প্রতিষ্ঠান ছাড়াই অনুশীলন ব্যবহার করা যায়। আয়োজক আমন্ত্রণ জানালে তাঁর লিংক খুলে একই ইমেইলে সাইন ইন করুন। তখন প্রতিষ্ঠানটি আপনার সাইডবারে দেখা যাবে।',
  'Have a join code from your organizer? Enter it below. A code lets you join as a student or test taker. Teachers and recruiters join by invitation.':
    'আয়োজকের কাছ থেকে জয়েন কোড পেয়েছেন? নিচে লিখুন। কোড দিয়ে শিক্ষার্থী বা পরীক্ষার্থী হিসাবে যোগ দেওয়া যায়। শিক্ষক ও নিয়োগকারীরা আমন্ত্রণের মাধ্যমে যোগ দেন।',
  'Join code': 'জয়েন কোড',
  'Join': 'যোগ দিন',
  'Start your own': 'নিজেরটা খুলুন',
  'Start an organization': 'একটি প্রতিষ্ঠান খুলুন',
  'Only start one if you will organize tests and invite people. Academy is for teaching and certificates. Talent is for hiring.':
    'শুধু তখনই খুলুন যদি আপনি পরীক্ষা পরিচালনা করেন আর মানুষকে ডাকেন। একাডেমি শেখানো ও সনদের জন্য, ট্যালেন্ট নিয়োগের জন্য।',
  'Organization name': 'প্রতিষ্ঠানের নাম',
  'e.g. Acme Academy': 'যেমন: অ্যাকমি একাডেমি',
  'Type': 'ধরন',
  'Create': 'তৈরি করুন',
  'You can be a learner in one organization and the owner of another. Your role in each stays separate.':
    'আপনি একটি প্রতিষ্ঠানে শিক্ষার্থী আর অন্যটির মালিক হতে পারেন। প্রতিটিতে আপনার ভূমিকা আলাদা থাকে।',
  'Use the code your organizer gave you. It lets you join as a student or test taker. Teachers and recruiters join by invitation.':
    'আয়োজকের দেওয়া কোডটি ব্যবহার করুন। এতে শিক্ষার্থী বা পরীক্ষার্থী হিসাবে যোগ দেওয়া যায়। শিক্ষক ও নিয়োগকারীরা আমন্ত্রণের মাধ্যমে যোগ দেন।',
  'Start your own organization': 'নিজের প্রতিষ্ঠান খুলুন',
  'Become the owner of a separate Academy or Talent organization. Your existing access stays as it is.':
    'আলাদা একটি একাডেমি বা ট্যালেন্ট প্রতিষ্ঠানের মালিক হোন। আপনার আগের অ্যাক্সেস যেমন আছে তেমনই থাকবে।',
  'Restoring your session…': 'আপনার সেশন ফিরিয়ে আনা হচ্ছে…',
  'Pricing': 'মূল্য',
  'How it works': 'কীভাবে কাজ করে',
  "How it's built": 'কীভাবে বানানো',
  'Technical assessment and certification.': 'প্রযুক্তিগত দক্ষতা যাচাই ও সনদ।',
  'Verify a certificate': 'সনদ যাচাই করুন',
  'All rights reserved.': 'সর্বস্বত্ব সংরক্ষিত।',
  // Sidebar chrome
  'Your organization': 'আপনার প্রতিষ্ঠান',
  'Your account': 'আপনার অ্যাকাউন্ট',
  // Home
  'Your next step': 'আপনার পরের ধাপ',
  'Your work': 'আপনার কাজ',
  'Explore': 'আরও দেখুন',
  'Open practice': 'অনুশীলন খুলুন',
  'Continue': 'চালিয়ে যান',
  'Open messages': 'বার্তা খুলুন',
  'Open my tests': 'আমার পরীক্ষা খুলুন',
  'Practice a skill': 'একটি দক্ষতা অনুশীলন করুন',
  'Join an organization': 'কোনো প্রতিষ্ঠানে যোগ দিন',
  'Run tests': 'পরীক্ষা পরিচালনা করুন',
  // Messages
  'Start a chat': 'নতুন কথোপকথন',
  'Start a chat with': 'কার সাথে কথা বলবেন',
  'Start': 'শুরু করুন',
  'Your conversations': 'আপনার কথোপকথন',
  'Select a conversation': 'একটি কথোপকথন বেছে নিন',
  'Pick someone from the list, or start a new chat.': 'তালিকা থেকে কাউকে বেছে নিন, অথবা নতুন কথোপকথন শুরু করুন।',
  'Write a message…': 'একটি বার্তা লিখুন…',
  'Send': 'পাঠান',
  'No messages yet. Say hello.': 'এখনো কোনো বার্তা নেই। একটি হ্যালো লিখুন।',
  'You': 'আপনি',
  'Open': 'খুলুন',
  'Enter to send, Shift+Enter for a new line': 'Enter চাপলে পাঠাবে, Shift+Enter চাপলে নতুন লাইন',
  // Sign-in
  'Sign in': 'সাইন ইন',
  'Create account': 'অ্যাকাউন্ট খুলুন',
  'Welcome back': 'আবার স্বাগতম',
  'Sign in with your verified email.': 'আপনার যাচাই করা ইমেইল দিয়ে সাইন ইন করুন।',
  'Email': 'ইমেইল',
  'Password': 'পাসওয়ার্ড',
  'Forgot password?': 'পাসওয়ার্ড ভুলে গেছেন?',
  'Create your account': 'আপনার অ্যাকাউন্ট খুলুন',
  'Technical assessment workspace': 'প্রযুক্তিগত দক্ষতা যাচাইয়ের প্ল্যাটফর্ম',
  // Common buttons
  'Save': 'সংরক্ষণ',
  'Cancel': 'বাতিল',
  'Retry': 'আবার চেষ্টা করুন',
  'Back': 'পিছনে'
};

let language = 'en';
try { language = localStorage.getItem('jachaiLang') === 'bn' ? 'bn' : 'en'; } catch { /* private window */ }

export const getLanguage = () => language;

// Placeholders are written {name}. Numbers become Bengali digits in Bangla, so a count in
// a Bangla sentence does not suddenly switch to Latin digits in the middle of it.
export function t(text, vars) {
  let out = language === 'bn' && Object.hasOwn(BN, text) ? BN[text] : text;
  if (vars) {
    out = out.replace(/\{(\w+)\}/g, (match, key) => {
      if (!(key in vars)) return match;
      const value = vars[key];
      return language === 'bn' && typeof value === 'number' ? value.toLocaleString('bn-BD') : String(value);
    });
  }
  return out;
}

// For dates and times. undefined lets the browser choose, which is right for English.
export const getLocale = () => (language === 'bn' ? 'bn-BD' : undefined);

// Re-labels every element that opted in. The English is remembered on first sight, so
// switching back restores the original rather than whatever was last displayed.
export function applyLanguage(root = document) {
  document.documentElement.lang = language === 'bn' ? 'bn' : 'en';
  for (const element of root.querySelectorAll('[data-t]')) {
    if (element.dataset.en === undefined) element.dataset.en = element.textContent.trim();
    element.textContent = t(element.dataset.en);
  }
  for (const attribute of ['placeholder', 'aria-label', 'title']) {
    for (const element of root.querySelectorAll(`[data-t-${attribute}]`)) {
      const key = `en${attribute.replace('-', '')}`;
      if (element.dataset[key] === undefined) element.dataset[key] = element.getAttribute(attribute) || '';
      element.setAttribute(attribute, t(element.dataset[key]));
    }
  }
}

export function setLanguage(next) {
  language = next === 'bn' ? 'bn' : 'en';
  try { localStorage.setItem('jachaiLang', language); } catch { /* not persisted; still applies now */ }
  applyLanguage();
  document.dispatchEvent(new CustomEvent('jachai:language', { detail: language }));
}
