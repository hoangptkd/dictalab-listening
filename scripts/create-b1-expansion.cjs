#!/usr/bin/env node
"use strict";
// Original content, bounded provider calls, cached outputs, verified S3 required before SQL import.
const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict');
const {createHash}=require('node:crypto'),{spawnSync}=require('node:child_process');
const {words,request,readWave,combine}=require('./create-b1-dictation.cjs');
const {render}=require('./create-listening-samples.cjs');
const root=path.resolve(process.argv[3]||'data/generated-b1-expansion-20261015');
const batchId='b1-expansion-20261015',hash=data=>createHash('sha256').update(data).digest('hex');
const quote=s=>"'"+String(s).replaceAll("'","''")+"'";
const topics={
 COMMUNICATION:[
  ['rental-viewing','Hẹn xem căn hộ','Arrange an apartment viewing around work hours and ask about noise and transport.'],
  ['flatmate-chores','Chia việc nhà','Flatmates negotiate a fair cleaning schedule after a busy week.'],
  ['lost-backpack','Tìm ba lô thất lạc','A traveller reports a backpack left on a bus and arranges collection.'],
  ['bike-repair','Sửa xe đạp','Explain a bicycle problem and choose a repair time within a budget.'],
  ['weekend-hike','Lên lịch đi bộ đường dài','Friends choose a walking route considering weather and different experience.'],
  ['cooking-class','Chọn lớp nấu ăn','Compare two cooking classes with different times and equipment requirements.'],
  ['library-renewal','Gia hạn sách thư viện','A reader negotiates book renewal and learns how reservations work.'],
  ['volunteer-shift','Đổi ca tình nguyện','Two volunteers exchange duties while making sure an event remains covered.'],
  ['moving-help','Nhờ bạn giúp chuyển nhà','Friends agree on transport and packing responsibilities for a move.'],
  ['phone-replacement','Đổi điện thoại bị lỗi','A customer explains a faulty phone and arranges a replacement collection.'],
  ['birthday-budget','Tổ chức sinh nhật vừa túi tiền','Friends plan a small birthday gathering within a clear budget.'],
  ['train-refund','Đổi vé tàu','A traveller changes an unused train ticket to a later service.'],
  ['gym-trial','Đăng ký tập thử','A visitor asks about trial gym sessions and chooses a suitable class.'],
  ['pet-sitting','Nhờ trông thú cưng','Neighbours arrange pet sitting during a short trip and clarify daily routines.'],
  ['neighbour-noise','Trao đổi về tiếng ồn','Neighbours politely solve a late evening noise problem.'],
  ['language-exchange','Lên lịch trao đổi ngôn ngữ','Two learners plan a language exchange with balanced speaking time.'],
  ['museum-visit','Chọn giờ đi bảo tàng','Friends compare transport and ticket options for a museum visit.'],
  ['picnic-change','Đổi kế hoạch dã ngoại','A family changes a picnic plan because of wind and agrees on an indoor alternative.'],
  ['parcel-return','Gửi trả hàng qua bưu điện','A customer learns how to pack and send back an unsuitable purchase.'],
  ['garden-project','Cùng trồng vườn nhỏ','Neighbours decide how to share a small community garden.'],
  ['photography-club','Tham gia câu lạc bộ ảnh','A newcomer asks about a photography club and its next beginner activity.'],
  ['driving-lesson','Đổi lịch học lái xe','A learner reschedules a driving lesson due to a work change without giving driving advice.'],
  ['homestay-arrival','Báo giờ đến nhà trọ','A guest explains a delayed arrival and agrees on a key collection arrangement.'],
  ['meal-preferences','Chọn món cho nhóm bạn','Friends choose a restaurant considering food preferences and cost.'],
  ['public-pool','Hỏi giờ bơi công cộng','A visitor compares pool sessions and asks about booking and lockers.'],
  ['sharing-tools','Mượn dụng cụ từ hàng xóm','Neighbours arrange borrowing tools and agree when to return them.'],
  ['concert-seats','Đổi chỗ ngồi xem hòa nhạc','Friends choose concert seats after comparing price and visibility.'],
  ['study-routine','Điều chỉnh lịch tự học','Two friends discuss a realistic study routine after a difficult week.'],
  ['charity-sale','Chuẩn bị bán hàng gây quỹ','Volunteers plan a small charity sale and divide preparation tasks.'],
  ['holiday-photos','Kể lại chuyến đi gần đây','Friends discuss a recent trip, a minor surprise, and what to do differently next time.']
 ],
 IELTS:[
  ['canoe-booking','Đặt chuyến chèo thuyền','Book a beginner canoe trip and clarify date, price, meeting place and equipment.'],
  ['evening-language-course','Đăng ký lớp ngoại ngữ buổi tối','Register for an evening language course with one corrected time and materials to bring.'],
  ['camping-reservation','Đặt chỗ cắm trại','Book a campsite with different pitches, a transport option and a revised arrival date.'],
  ['music-workshop','Đăng ký hội thảo âm nhạc','Ask about a music workshop, room location, fee and items participants need.'],
  ['walking-tour','Đặt tour đi bộ trong thành phố','Book a walking tour with a changed start point, duration and payment detail.'],
  ['host-family-enquiry','Hỏi thông tin ở cùng gia đình','Discuss a short homestay booking, room needs, transport and an arrival time correction.'],
  ['sports-centre-membership','Đăng ký trung tâm thể thao','Enquire about sports centre membership and record a chosen plan and class schedule.'],
  ['weekend-art-course','Đăng ký khóa vẽ cuối tuần','Arrange an art course booking, supplies, tutor name and room location.'],
  ['student-bike-hire','Thuê xe đạp cho sinh viên','Enquire about bicycle hire, deposit, return location and a booking change.'],
  ['theatre-group-enquiry','Hỏi lịch câu lạc bộ kịch','Ask about a theatre group, rehearsal time, registration and clothing requirements.'],
  ['local-history-centre','Giới thiệu trung tâm lịch sử','A guide introduces a local history centre, opening times and visitor facilities.'],
  ['community-festival','Thông tin ngày hội cộng đồng','An organiser gives practical details of a community festival with one programme change.'],
  ['new-library-tour','Hướng dẫn thư viện mới','A librarian introduces facilities, study areas and the borrowing process.'],
  ['nature-reserve-visit','Hướng dẫn thăm khu bảo tồn','A guide explains a nature reserve visit, meeting points and visitor arrangements.'],
  ['volunteer-induction','Hướng dẫn tình nguyện viên mới','An organiser explains volunteer arrival, tasks, breaks and where to get help.'],
  ['student-housing-orientation','Giới thiệu khu nhà sinh viên','A staff member introduces student housing shared facilities and reporting procedures.'],
  ['town-leisure-programme','Giới thiệu chương trình giải trí','A coordinator describes a local leisure programme and booking arrangements.'],
  ['college-open-day','Hướng dẫn ngày hội trường học','A host explains college open day activities, rooms and a revised session time.'],
  ['recycling-centre-tour','Hướng dẫn trung tâm tái chế','A guide introduces a recycling centre and the steps visitors follow.'],
  ['summer-market','Thông báo chợ mùa hè','An organiser outlines a summer market, stalls, transport and meeting location.'],
  ['survey-project','Thảo luận khảo sát thói quen đọc','Two students agree a reading habits survey method and divide project tasks.'],
  ['presentation-plan','Lên kế hoạch thuyết trình nhóm','Two students compare presentation ideas, reject one option and agree a clear plan.'],
  ['field-trip-report','Thảo luận báo cáo chuyến thực tế','Two students organise a field trip report and distinguish observation from opinion.'],
  ['interview-assignment','Chuẩn bị bài phỏng vấn','Two learners discuss interview questions and practical arrangements for a college assignment.'],
  ['design-feedback','Trao đổi phản hồi thiết kế','Two students discuss tutor feedback and choose achievable changes for a poster.'],
  ['sleep-and-learning','Bài giảng về giấc ngủ và học tập','An accessible introductory lecture about everyday study habits and sleep, no medical advice or unsupported numerical claims.'],
  ['urban-green-spaces','Bài giảng về không gian xanh','An introductory lecture describes uses of small urban green spaces and ways to observe them.'],
  ['repair-and-reuse','Bài giảng về sửa chữa và tái sử dụng','An accessible lecture explains repair and reuse with a hypothetical community example.'],
  ['everyday-memory','Bài giảng về ghi nhớ công việc','An introductory talk explains practical ways to remember everyday tasks using examples, not scientific certainty.'],
  ['food-waste-study','Bài giảng về khảo sát đồ ăn thừa','An accessible lecture outlines a hypothetical campus food waste observation project and its limitations.']
 ],
 TOEIC:[
  ['printer-support','Hỏi đáp về máy in','Four independent workplace question-response items about printer help, supplies, location and responsibility.'],
  ['meeting-arrangements','Hỏi đáp sắp xếp cuộc họp','Four independent workplace question-response items about meeting room, start time, participants and agenda.'],
  ['office-visitors','Hỏi đáp đón khách văn phòng','Four independent question-response items about visitor arrival, reception, badges and host availability.'],
  ['shipment-status','Hỏi đáp theo dõi đơn hàng','Four independent question-response items about shipment delay, tracking, collection and recipient.'],
  ['staff-training','Hỏi đáp đào tạo nhân viên','Four independent question-response items about training signup, trainer, equipment and schedule.'],
  ['expense-forms','Hỏi đáp biểu mẫu công tác','Four independent question-response items about form location, submission deadline, approval and receipts.'],
  ['lunch-orders','Hỏi đáp đặt bữa trưa','Four independent question-response items about lunch orders, delivery, payment and dietary choices.'],
  ['hotel-services','Hỏi đáp dịch vụ khách sạn','Four independent question-response items about breakfast, luggage, shuttle and room readiness.'],
  ['equipment-booking','Hỏi đáp đặt thiết bị','Four independent question-response items about projector booking, collection, return and availability.'],
  ['business-travel','Hỏi đáp chuyến công tác','Four independent question-response items about tickets, itinerary, travel time and a colleague.'],
  ['conference-delivery','Hội thoại giao hàng cho hội nghị','Two distinct short workplace conversations: delayed conference materials and a changed delivery address.'],
  ['customer-appointment','Hội thoại đổi lịch hẹn khách hàng','Two distinct short workplace conversations: rescheduling a customer appointment and preparing its documents.'],
  ['store-stock','Hội thoại kiểm tra hàng trong kho','Two short retail conversations: replacing an out of stock item and checking a collection date.'],
  ['team-handover','Hội thoại bàn giao công việc','Two short workplace conversations: covering a colleague shift and handing over a customer task.'],
  ['office-move','Hội thoại chuyển chỗ làm việc','Two short workplace conversations: organising desk labels and arranging equipment transport.'],
  ['catering-change','Hội thoại đổi suất ăn sự kiện','Two short workplace conversations: changing guest numbers and confirming revised catering choices.'],
  ['travel-desk','Hội thoại với bộ phận đặt vé','Two short workplace conversations: changing a business train booking and collecting the revised itinerary.'],
  ['maintenance-visit','Hội thoại hẹn bảo trì thiết bị','Two short workplace conversations: scheduling a routine equipment visit and arranging access.'],
  ['website-update','Hội thoại cập nhật website','Two short workplace conversations: correcting event information and agreeing who will check the page.'],
  ['sales-demo','Hội thoại chuẩn bị giới thiệu sản phẩm','Two short workplace conversations: choosing a demonstration room and arranging sample delivery.'],
  ['airport-shuttle','Thông báo xe đưa đón sân bay','Two short single-speaker announcements: hotel shuttle changes and airport pickup instructions.'],
  ['conference-opening','Thông báo khai mạc hội nghị','Two short single-speaker announcements: conference registration and a changed presentation room.'],
  ['store-renovation','Thông báo sửa chữa cửa hàng','Two short single-speaker announcements: temporary store arrangements and a reopening event.'],
  ['staff-orientation','Thông báo hướng dẫn nhân viên','Two short single-speaker talks: new staff arrival instructions and a training session reminder.'],
  ['delivery-message','Tin nhắn về giao hàng','Two short single-speaker voicemail messages: a delayed delivery and collection alternatives.'],
  ['museum-audio-guide','Thông báo dịch vụ bảo tàng','Two short single-speaker visitor announcements: exhibition access and an audio guide return procedure.'],
  ['business-workshop','Thông báo hội thảo doanh nghiệp','Two short single-speaker announcements: workshop preparation and a schedule adjustment.'],
  ['station-platform','Thông báo thay đổi sân ga','Two short single-speaker station announcements: platform changes and replacement bus arrangements.'],
  ['community-advertisement','Quảng cáo dịch vụ cộng đồng','Two short single-speaker advertisements: a local repair service and an evening learning course.'],
  ['office-facilities','Thông báo tiện ích văn phòng','Two short single-speaker staff announcements: meeting room maintenance and temporary desk arrangements.']
 ]
};
function specifications(){
 return Object.entries(topics).flatMap(([goal,list])=>list.map(([id,title,context],i)=>{
  const target=goal==='COMMUNICATION'?'B1':goal==='IELTS'?(i<15?'4.0':'5.0'):(i<15?'600':'700');
  const part=goal==='IELTS'?(i<10?1:i<20?2:i<25?3:4):goal==='TOEIC'?(i<10?2:i<20?3:4):null;
  const courseId=goal==='IELTS'?'ielts-band-'+target.replace('.','-'):goal==='TOEIC'?'toeic-score-'+target:'communication-cefr-b1';
  return {slug:`b1-${goal.toLowerCase()}-${id}-20261015`,goal,target,courseId,level:'B1',title,context,part,
   speed:goal==='TOEIC'?1.0:0.97,format:goal==='COMMUNICATION'?'Hội thoại B1':`${goal} Listening Part ${part} · bài luyện rút gọn`,
   focus:goal==='COMMUNICATION'?'Nghe lý do, so sánh lựa chọn và quyết định cuối cùng':goal==='IELTS'?'Theo dõi thông tin theo thứ tự, nhận ra sửa đổi và diễn đạt lại':'Nghe mục đích, thông tin cụ thể và hành động tiếp theo',
   groupId:courseId+'-b1-'+(goal==='COMMUNICATION'?String(Math.floor(i/10)+1):'part-'+part)};
 }));
}
function validateLesson(l){
 assert(l.level==='B1'&&/^b1-(communication|ielts|toeic)-[a-z0-9-]+-20261015$/.test(l.slug));
 assert(l.title.length<=140&&l.focus.length<=180&&l.sentences.length>=12&&l.sentences.length<=28);
 const unique=new Set();
 for(const s of l.sentences){
  assert(['A','B'].includes(s.speaker));
  assert(typeof s.text==='string'&&s.text===s.text.trim()&&/^[A-Z][\x20-\x7E]+[.!?]$/.test(s.text),'English text invalid');
  assert(!/[.!?]/.test(s.text.slice(0,-1))&&!/[<>\n\d:]/.test(s.text),'One sentence with spelled-out numbers and titles required: '+s.text);
  assert(words(s.text).length>=3&&words(s.text).length<=28,'Sentence length');
  assert(!unique.has(s.text.toLowerCase()),'Repeated sentence');unique.add(s.text.toLowerCase());
  assert(s.pauseAfterSeconds==null||(s.pauseAfterSeconds>=0&&s.pauseAfterSeconds<=8));
 }
 const count=l.sentences.reduce((n,s)=>n+words(s.text).length,0);
 assert(count>=120&&count<=330,'Total length');
 assert(l.questions.length===(l.goal==='TOEIC'&&l.part===2?4:6),'Question count');
 let previous=0;
 for(const q of l.questions){
  assert(Number.isInteger(q.evidenceSentence)&&q.evidenceSentence>=previous,'Evidence order');previous=q.evidenceSentence;
  const s=l.sentences[q.evidenceSentence-1];
  assert(typeof q.evidence==='string'&&q.evidence.length>=2&&s?.text.toLowerCase().includes(q.evidence.toLowerCase()),'Evidence not in audio: '+JSON.stringify(q));
  assert(typeof q.prompt==='string'&&q.prompt.length>=8&&q.prompt.length<=180);
  assert(typeof q.explanation==='string'&&q.explanation.length>=8);
  if(q.options){assert(!(l.goal==='IELTS'&&[1,2,4].includes(l.part)),'IELTS completion required');assert.equal(q.options.length,3);assert(/^[ABC]$/.test(q.answer));assert.equal(new Set(q.options).size,3)}
  else{assert(l.goal==='IELTS');assert(q.prompt.includes('___'),'Completion prompt must contain a blank');assert(typeof q.answer==='string'&&words(q.answer).length>=1&&words(q.answer).length<=3,'Gap answer must contain one to three words: '+JSON.stringify(q));assert(q.evidence.toLowerCase().includes(q.answer.toLowerCase()),'Gap answer absent: '+JSON.stringify(q))}
 }
 if(l.goal==='COMMUNICATION'||(l.goal==='IELTS'&&(l.part===1||l.part===3)))assert.equal(new Set(l.sentences.map(s=>s.speaker)).size,2);
 if((l.goal==='IELTS'&&(l.part===2||l.part===4))||(l.goal==='TOEIC'&&l.part===4))assert(l.sentences.every(s=>s.speaker==='A'));
 if(l.goal==='TOEIC'&&l.part===2){
  assert.equal(l.sentences.length,16);
  for(let n=0;n<4;n++){
   const offset=n*4,q=l.questions[n];assert.equal(l.sentences[offset].role,'question');
   assert.equal(q.evidenceSentence,offset+1);
   for(let j=0;j<3;j++)assert.equal(l.sentences[offset+j+1].text,q.options[j]);
  }
 }
 if(l.goal==='TOEIC'&&[3,4].includes(l.part)){
  const boundaries=l.sentences.map((s,i)=>s.role==='section-start'?i:-1).filter(i=>i>=0);
  assert.equal(boundaries.length,1,'Two sections require one section-start boundary');
  assert(boundaries[0]>=6&&boundaries[0]<=l.sentences.length-6,'Two complete short sections required');
  assert.equal(l.questions.filter(q=>q.evidenceSentence<=boundaries[0]).length,3,'Three questions per section required');
 }
 return count;
}
function validateBatch(b){
 assert.equal(b.batchId,batchId);assert.equal(b.calibrated,false);assert.equal(b.lessons.length,90);
 const specs=specifications(),all=new Set(),transcripts=new Set();let characters=0,calls=0;
 for(const l of b.lessons){
  const expected=specs.find(x=>x.slug===l.slug);assert(expected);
  for(const key of ['goal','level','target','courseId','part','groupId'])assert.equal(l[key],expected[key]);
  validateLesson(l);assert(!all.has(l.slug));all.add(l.slug);
  const fingerprint=hash(l.sentences.map(s=>s.text.toLowerCase()).join('\n'));assert(!transcripts.has(fingerprint),'Duplicate complete transcript');transcripts.add(fingerprint);
  characters+=l.sentences.reduce((n,s)=>n+s.text.length,0);calls+=l.sentences.length;
 }
 assert(characters<=200000&&calls<=2100,'Bounded TTS batch budget exceeded');
 return {billableCharacters:characters,maximumProviderCalls:calls};
}
const instruction=`Write ORIGINAL English listening practice for B1 learners, not copies of published tests.
Return only JSON {"instructions":"Vietnamese learner instructions","sentences":[{"speaker":"A","text":"One complete English sentence."}],"questions":[{"prompt":"English question","options":["choice","choice","choice"],"answer":"B","evidenceSentence":2,"evidence":"exact transcript substring","explanation":"Vietnamese explanation"}]}.
Each text is one grammatical ASCII English sentence, 3-28 words, no internal punctuation ending sentences, no digits, abbreviations or stage directions; spell numbers out.
Keep B1 vocabulary and varied because, although, if, when clauses and some past/perfect forms. Never give medical/legal advice or invent factual research claims.
For continuous dialogues/talks: 14-20 sentences, 150-260 words; six questions in audio evidence order with three plausible distinct choices and only one defensible answer. Include purpose, detail, a paraphrase or simple implication, and next action; do not make all answers the same letter.
Communication: two speakers alternate naturally, coherent problem, explanation, options and outcome.
IELTS Part 1: two alternating speakers, booking enquiry; six completion questions with ONE OR TWO WORDS, answers exactly in transcript, no options; a corrected detail and distractor rejected explicitly.
IELTS Part 2: one speaker A only, practical visitor/organiser information; six ONE OR TWO WORDS completion questions, answers exactly in transcript, no options.
IELTS Part 3: two speakers, educational discussion; six multiple choice questions, clear final agreement and one initially rejected suggestion.
IELTS Part 4: one speaker A, accessible introductory lecture; six ONE OR TWO WORDS note completion questions, no options, exact answers in transcript; hypothetical examples only.
TOEIC Part 2: exactly FOUR items, each FOUR sentence entries: question speaker A role question, then three spoken answer choices speaker B role choice, one of which is a natural correct response. Never read the correct answer separately. Each choice one sentence, 7-15 words. No spoken numbering or letters. Exactly sixteen entries, 120-210 words total. Four questions with prompt 'Choose the best response to question one/two/three/four.' and three options EXACTLY matching the last three entries for that item. evidenceSentence points to the question, evidence is an exact question substring. Vary correct letters. Choices are audio only for learners (oralOnly=true).
TOEIC Part 3: TWO independent workplace conversations, seven or eight entries each, speakers alternate inside each conversation; first entry of second conversation role='section-start'. Three questions for each conversation, six total, chronological evidence.
TOEIC Part 4: TWO independent workplace announcements/talks, seven or eight entries each, all speaker A; first entry of second talk role='section-start'. Three questions per talk, six total.
Questions are never part of continuous dialogue audio. evidenceSentence is one-based exact sentence number. Keep all question evidence in chronological order.
Avoid simple word-copy distractors; an option must not be true elsewhere in a way that makes two answers reasonable. Return only this lesson, no title or metadata.`;

async function cachedJson(file,system,user,model,maxTokens=5500){
 let body;try{body=JSON.parse(await fs.readFile(file,'utf8'))}catch(e){
  if(e.code!=='ENOENT')throw e;
  body=JSON.parse((await request(process.env.SHOPAIKEY_URL||'https://api.shopaikey.com/v1/chat/completions',process.env.SHOPAIKEY_API_KEY,
   {model,temperature:0.35,max_tokens:maxTokens,messages:[{role:'system',content:system},{role:'user',content:user}]},{},150000)).toString('utf8'));
  await fs.writeFile(file,JSON.stringify(body,null,2)+'\n',{encoding:'utf8',flag:'wx'});
 }
 const content=body.choices?.[0]?.message?.content;assert(typeof content==='string'&&content.length<=40000);
 return JSON.parse(content.slice(content.indexOf('{'),content.lastIndexOf('}')+1));
}
async function generate(){
 assert(process.env.SHOPAIKEY_API_KEY,'Missing text provider runtime configuration');
 await fs.mkdir(path.join(root,'text-cache'),{recursive:true});
 const model=process.env.DICTALAB_CONVERSATION_MODEL||'gemini-2.5-flash',lessons=[];
 const specs=specifications();
 async function one(spec){
  let l,last='';
  for(let attempt=0;attempt<5;attempt++){
   let manual;try{manual=JSON.parse(await fs.readFile(path.join(root,'text-cache',spec.slug+'-manual.json'),'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
   const parsed=manual||await cachedJson(path.join(root,'text-cache',spec.slug+`-${attempt}.json`),instruction,
    `${spec.goal}, ${spec.format}, target ${spec.target}, CEFR B1. Scenario: ${spec.context}.\n${spec.goal==='IELTS'&&[1,2,4].includes(spec.part)?'Completion prompts MUST be short field or note labels containing ______, not open questions. For example: Fee per person: ______ pounds. Answer forty with evidence forty pounds. Use one or two words exactly as spoken, including spoken English numbers, never digits in the answer.':''}\n${last}`,model);
   try{
   const ascii=s=>{assert.equal(typeof s,'string','Expected text field');return s.replaceAll('’',"'").replaceAll('‘',"'").replaceAll('“','"').replaceAll('”','"').replaceAll('–','-').replaceAll('—','-').replace(/\bMr\./g,'Mister').replace(/\bMrs\./g,'Missus').replace(/\bMs\./g,'Ms').replace(/\bDr\./g,'Doctor')};
   for(const s of parsed.sentences||[])s.text=ascii(s.text).trim().replace(/([A-Za-z])$/,'$1.');
   for(const s of parsed.sentences||[]){
    if(s['section-start']===true||s.sectionStart===true)s.role='section-start';
    if(s.speaker==='section-start'){s.speaker='A';s.role='section-start'}
    if(s.speaker==='C')s.speaker='A';if(s.speaker==='D')s.speaker='B';
   }
   if(spec.goal==='TOEIC'&&spec.part===2&&parsed.sentences.length===16)parsed.sentences.forEach((s,i)=>{s.role=i%4===0?'question':'choice';s.speaker=i%4===0?'A':'B'});
   for(const q of parsed.questions||[]){if(Array.isArray(q.options)&&q.options.length===0)delete q.options;q.evidence=ascii(q.evidence);if(q.options)q.options=q.options.map(s=>ascii(s).trim().replace(/([A-Za-z])$/,'$1.'));else q.answer=ascii(q.answer)}
   for(const q of parsed.questions||[]){
    if(!parsed.sentences[q.evidenceSentence-1]?.text.toLowerCase().includes(q.evidence.toLowerCase())){
     const found=parsed.sentences.map((s,i)=>s.text.toLowerCase().includes(q.evidence.toLowerCase())?i+1:0).filter(Boolean);
     if(found.length===1)q.evidenceSentence=found[0];
    }
   }
   parsed.questions?.sort((a,b)=>a.evidenceSentence-b.evidenceSentence);
   l={...spec,instructions:parsed.instructions,sentences:parsed.sentences,questions:parsed.questions,oralOnly:spec.goal==='TOEIC'&&spec.part===2};
   if(spec.goal==='IELTS'&&[1,2,4].includes(spec.part))l.instructions='Nghe và điền KHÔNG QUÁ BA TỪ cho mỗi chỗ trống. Viết số bằng chữ tiếng Anh. Đáp án xuất hiện theo thứ tự trong audio.';
    validateLesson(l);
    const review=await cachedJson(path.join(root,'text-cache',spec.slug+`${manual?'-manual':''}-review-${attempt}.json`),
     `You are an English listening editor. Check this ORIGINAL B1 practice. Return JSON {"pass":true,"issues":[]} or {"pass":false,"issues":["specific corrections"]}. Check natural coherence, each question's unique correct answer, evidence positions and answer keys, distractors, chronological order, required exam form, no misleading claims. Check Part 2 TOEIC correct choice matches response and other two are wrong for question. Completion answers must fit blanks grammatically. Reject only substantive defects.`,JSON.stringify(l),model,1700);
    assert(review.pass===true,'Editor issues: '+JSON.stringify(review.issues));break;
   }catch(e){if(attempt===4)throw Error(spec.slug+': '+e.message);last=`Previous rejected JSON: ${JSON.stringify(parsed)}\nFix these validation/editor issues: ${e.message}. Keep all sentences grammatical, all answers supported, and completion prompts containing blanks.`;console.log(`REPAIR ${spec.slug}: ${e.message}`)}
  }
  l.wordCount=validateLesson(l);lessons.push(l);
  await fs.writeFile(path.join(root,'generation-progress.json'),JSON.stringify({completed:lessons.length,total:90})+'\n');
  console.log(`TEXT_REVIEWED ${lessons.length}/90 ${spec.slug}`);
 }
 for(let offset=0;offset<specs.length;offset+=3){
  const results=await Promise.allSettled(specs.slice(offset,offset+3).map(one));
  const failed=results.find(r=>r.status==='rejected');if(failed)throw failed.reason;
 }
 lessons.sort((a,b)=>specs.findIndex(s=>s.slug===a.slug)-specs.findIndex(s=>s.slug===b.slug));
 const brief={batchId,calibrated:false,generationModel:model,generatedAt:new Date().toISOString(),sources:[
  {title:'ETS · TOEIC và CEFR',url:'https://www.ets.org/content/dam/ets-org/pdfs/toeic/toeic-mapping-cefr-reference.pdf'},
  {title:'IELTS · CEFR tham khảo',url:'https://ielts.org/organisations/ielts-for-organisations/compare-ielts/ielts-and-the-cefr'},
  {title:'IELTS Listening · cấu trúc',url:'https://ielts.org/take-a-test/test-types/ielts-academic-test/ielts-academic-format-listening'}
 ],lessons};
 brief.budget=validateBatch(brief);await fs.writeFile(path.join(root,'brief.json'),JSON.stringify(brief,null,2)+'\n','utf8');
 console.log(JSON.stringify(brief.budget));
}
async function audio(){
 const brief=JSON.parse(await fs.readFile(path.join(root,'brief.json'),'utf8')),budget=validateBatch(brief);
 assert(process.env.CARTESIA_API_KEY,'Missing audio provider runtime configuration');
 const model=process.env.CARTESIA_MODEL||'sonic-3.6',version=process.env.CARTESIA_VERSION||'2026-08-14';
 const voices={A:process.env.CARTESIA_VOICE||'db6b0ed5-d5d3-463d-ae85-518a07d3c2b4',B:'47c38ca4-5f35-497b-b1a3-415245fb35e1'};
 const manifest={...brief,audioModel:model,voices,lessons:[]};await fs.mkdir(path.join(root,'segments'),{recursive:true});let completed=0;
 for(const lesson of brief.lessons){
  const spoken=lesson.sentences.map((s,i)=>({...s,pauseAfterSeconds:s.role==='section-start'?0.2:lesson.sentences[i+1]?.role==='section-start'?5:lesson.oralOnly&&i%4===3?5:lesson.oralOnly&&i%4===0?0.7:0.2}));
  const segments=[];
  for(let offset=0;offset<spoken.length;offset+=2){
   const group=await Promise.all(spoken.slice(offset,offset+2).map(async s=>{
    const payload={model_id:model,transcript:s.text,voice:voices[s.speaker],language:'en',generation_config:{speed:lesson.speed},output_format:{container:'wav',encoding:'pcm_s16le',sample_rate:24000}};
    const file=path.join(root,'segments',hash(JSON.stringify({payload,version}))+'.wav');let wave;
    try{wave=await fs.readFile(file)}catch(e){if(e.code!=='ENOENT')throw e;wave=await request('https://api.cartesia.ai/tts/bytes',process.env.CARTESIA_API_KEY,payload,{'Cartesia-Version':version},3000000);readWave(wave);await fs.writeFile(file,wave,{flag:'wx'})}
    completed++;return readWave(wave);
   }));segments.push(...group);
  }
  const combined=combine(spoken,segments),audioFile=lesson.slug+'.wav';
  await fs.writeFile(path.join(root,audioFile),combined.wave);
  manifest.lessons.push({...lesson,audioFile,sha256:hash(combined.wave),durationSeconds:combined.durationSeconds,sentences:combined.aligned});
  await fs.writeFile(path.join(root,'manifest.json'),JSON.stringify(manifest,null,2)+'\n','utf8');
  console.log(`AUDIO_READY ${manifest.lessons.length}/90 ${lesson.slug} ${combined.durationSeconds.toFixed(2)}s ${completed}/${budget.maximumProviderCalls}`);
 }
}

function buildSql(m){
 validateBatch(m);let sql="\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL lock_timeout='3s';\nSET LOCAL statement_timeout='60s';\nLOCK TABLE lessons,lesson_sentences,library_courses,library_groups,library_lessons,library_lesson_metadata IN SHARE ROW EXCLUSIVE MODE;\n";
 sql+="INSERT INTO library_courses(id,goal,title,target_label,description,criteria,coverage_note,position_index,published,ready) SELECT 'toeic-score-600','TOEIC','TOEIC 600','Mục tiêu luyện B1 · tham khảo','Bài luyện nghe riêng theo cấu trúc TOEIC; mốc tổng Listening & Reading tham khảo, không dự đoán điểm thi.','Ngôn ngữ B1, nghe chi tiết và mục đích; câu hỏi và phản hồi, hội thoại công việc.','Đợt bài B1 mới; chưa hiệu chuẩn điểm thi.',3,TRUE,FALSE WHERE NOT EXISTS(SELECT 1 FROM library_courses WHERE id='toeic-score-600');\n";
 for(const [groupId,l] of new Map(m.lessons.map(l=>[l.groupId,l]))){
  sql+=`DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM library_courses WHERE id=${quote(l.courseId)} AND goal=${quote(l.goal)} AND published) THEN RAISE EXCEPTION 'Course unavailable'; END IF; END $$;\n`;
  sql+=`INSERT INTO library_groups(id,course_id,title,position_index) VALUES(${quote(groupId)},${quote(l.courseId)},${quote(l.goal==='COMMUNICATION'?'B1 · '+['Sinh hoạt và sắp xếp','Bạn bè và cộng đồng','Trải nghiệm và dịch vụ'][Number(groupId.slice(-1))-1]:`${l.goal} Part ${l.part} · luyện B1`)},(SELECT COALESCE(MAX(position_index),0)+1 FROM library_groups WHERE course_id=${quote(l.courseId)}));\n`;
 }
 for(const l of m.lessons){
  assert.equal(l.audioFile,l.slug+'.wav');assert.match(l.sha256,/^[a-f0-9]{64}$/);
  sql+=`DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM audio_s3_aliases WHERE audio_url=${quote('/media/'+l.audioFile)} AND sha256=${quote(l.sha256)}) THEN RAISE EXCEPTION 'S3 verification required'; END IF; END $$;\n`;
  const speakers=new Set(l.sentences.map(s=>s.speaker)).size;
  const accent=l.accent||'General American (AI)',provider=l.audioProvider||'Cartesia';
  sql+=`INSERT INTO lessons(slug,title,description,level,topic,estimated_minutes,display_order,active,content_origin,speaker_count,accent_profile,vocabulary_difficulty,grammar_difficulty,sentence_difficulty,assessment_calibrated) VALUES(${quote(l.slug)},${quote(l.title)},${quote(l.format+'. Nội dung gốc B1, giọng AI; mục tiêu luyện tham khảo, chưa hiệu chuẩn điểm thi.')},'B1',${quote(l.goal==='COMMUNICATION'?'Giao tiếp đời sống':l.goal+' · '+l.target)},8,(SELECT COALESCE(MAX(display_order),0)+1 FROM lessons WHERE level='B1'),TRUE,'ADMIN_UPLOAD',${speakers},${quote(accent+' / '+provider)},3,3,3,FALSE);\n`;
  let previous=0;
  for(const [i,s] of l.sentences.entries()){
   assert(s.audioStartSeconds>=previous&&s.audioEndSeconds>s.audioStartSeconds&&s.audioEndSeconds<=l.durationSeconds);previous=s.audioEndSeconds;
   sql+=`INSERT INTO lesson_sentences(lesson_id,position_index,transcript,audio_url,speech_rate,audio_start_seconds,audio_end_seconds) SELECT id,${i+1},${quote(s.text)},${quote('/media/'+l.audioFile)},${l.speed},${s.audioStartSeconds},${s.audioEndSeconds} FROM lessons WHERE slug=${quote(l.slug)};\n`;
  }
  const notes=`Original ${batchId}; automated linguistic/editor review and full offline ASR/integrity check. WAV SHA256 ${l.sha256}. ${accent}, ${provider}; not exam-score calibrated. Exercises: /audio/${batchId}/${l.slug}.html`;
  sql+=`INSERT INTO library_lesson_metadata(lesson_id,topic,focus,source_label,accent,speaker_count,audit_notes,reviewed,lesson_updated_at) SELECT id,${quote(l.title)},${quote(l.focus)},${quote('DictaLab · Bài gốc B1 / '+provider)},${quote(accent)},${speakers},${quote(notes)},TRUE,updated_at FROM lessons WHERE slug=${quote(l.slug)};\n`;
  sql+=`INSERT INTO library_lessons(course_id,group_id,lesson_id,position_index,difficulty,purpose) SELECT ${quote(l.courseId)},${quote(l.groupId)},id,(SELECT COALESCE(MAX(position_index),0)+1 FROM library_lessons WHERE group_id=${quote(l.groupId)}),3,${quote(l.format+' · '+l.focus)} FROM lessons WHERE slug=${quote(l.slug)};\n`;
 }
 for(const l of [...new Map(m.lessons.map(l=>[l.courseId,l])).values()]){
  const parts=[...new Set(m.lessons.filter(x=>x.courseId===l.courseId).map(x=>x.part))].sort().join(' / ');
  sql+=`UPDATE library_courses SET ready=TRUE,revision=revision+1,${l.goal!=='COMMUNICATION'?`target_label=${quote('Luyện B1 · Part '+parts+' · tham khảo')},`:''}coverage_note=${quote(`Đã bổ sung ${m.lessons.filter(x=>x.courseId===l.courseId).length} bài gốc B1 có transcript và câu hỏi. Mục tiêu luyện tham khảo; chưa hiệu chuẩn điểm thi, không phải đề thi đầy đủ.`)} WHERE id=${quote(l.courseId)};\n`;
 }
 sql+="DO $$ BEGIN IF EXISTS(SELECT lesson_id FROM library_lessons x JOIN library_courses c ON c.id=x.course_id GROUP BY lesson_id HAVING count(DISTINCT c.goal)>1) THEN RAISE EXCEPTION 'Cross-goal classification'; END IF; END $$;\nCOMMIT;\n";return sql;
}
async function exportBatch(){
 const manifest=JSON.parse(await fs.readFile(path.join(root,'manifest.json'),'utf8'));validateBatch(manifest);
 for(const l of manifest.lessons){
  const data=await fs.readFile(path.join(root,l.audioFile));assert.equal(hash(data),l.sha256);readWave(data);
  const output=path.join(root,l.slug+'.mp3');
  const r=spawnSync('ffmpeg',['-hide_banner','-loglevel','error','-y','-i',path.join(root,l.audioFile),'-codec:a','libmp3lame','-q:a','2',output],{encoding:'utf8',windowsHide:true});assert.equal(r.status,0,'MP3 conversion failed');
  const d=spawnSync('ffmpeg',['-hide_banner','-loglevel','error','-i',output,'-f','null','-'],{encoding:'utf8',windowsHide:true});assert.equal(d.status,0);
  l.mp3File=l.slug+'.mp3';l.mp3Sha256=hash(await fs.readFile(output));
 }
 await fs.writeFile(path.join(root,'manifest.json'),JSON.stringify(manifest,null,2)+'\n','utf8');
 await fs.writeFile(path.join(root,'publish-import.sql'),buildSql(manifest),'utf8');
 const out=path.join(root,'web');await fs.mkdir(out,{recursive:true});
 const auth=(await fs.readFile(path.resolve('artifacts/listening-samples-deploy-20261015/web/player.js'),'utf8')).split('const authStatus=')[1];
 const transcript=['# DictaLab · 90 bài B1\n\nNội dung gốc, giọng AI; chưa hiệu chuẩn điểm thi.'];
 for(const l of manifest.lessons){
  let page=render({...manifest,lessons:[l]});
  const script=page.match(/<script>([\s\S]*?)<\/script>/)[1];
  const js=script+'\nconst authStatus='+auth.replace("audio.preload='metadata'","audio.preload='none'");
  await fs.writeFile(path.join(out,l.slug+'.js'),js,'utf8');
  page=page.replace(/<script>[\s\S]*?<\/script>/,`<script src="${l.slug}.js" defer></script>`);
  page=page.replace(/<nav class="filters"[\s\S]*?<\/nav>/,'<p><a href="index.html">← Danh sách 90 bài B1</a> · <a href="/catalog">Thư viện luyện chép chính tả</a></p>');
  page=page.replace(/<p id="filter-status"[\s\S]*?<\/p>/,'<p id="filter-status" role="status" hidden></p>');
  page=page.replace('DictaLab · 9 bài nghe mẫu',`DictaLab · ${l.title}`).replace(/<h1>[\s\S]*?<\/h1>/,`<h1>${l.title}</h1>`);
  page=page.replace('9 bài gốc, 3 hướng luyện. Nghe toàn bài, tự trả lời, rồi mở transcript để luyện từng đoạn.','Nghe toàn bài, trả lời câu hỏi, rồi mở transcript để luyện từng đoạn.').replace('Giọng tổng hợp Cartesia.',`Giọng tổng hợp ${l.audioProvider} · ${l.accent}.`);
  page=page.replace('</style>',' .grid{grid-template-columns:1fr;max-width:850px}</style>');
  if(l.oralOnly)page=page.replace(/<span class="options">[\s\S]*?<\/span>/g,'');
  page=page.replace('<div class="grid">','<p id="auth-status" class="notice" role="status" hidden></p><div class="grid">');
  page=page.replace('<audio controls preload="metadata"','<audio controls preload="none" hidden').replace(/(<audio[^>]*?) src=/,'$1 data-src=');
  page=page.replaceAll(' download>',' download hidden>').replaceAll('type="button" data-segment=','type="button" disabled data-segment=');
  for(const name of [l.audioFile,l.mp3File])page=page.replaceAll('"'+name+'"','"/media/'+name+'"');
  page=page.replaceAll('research.md','index.html');
  assert(!page.includes('<script>'));await fs.writeFile(path.join(out,l.slug+'.html'),page,'utf8');
  transcript.push(`## ${l.goal} ${l.target} · ${l.title}\n\n${l.sentences.map((s,i)=>`${i+1}. **${s.speaker}**: ${s.text}`).join('\n')}\n\n${l.questions.map((q,i)=>`${i+1}. ${q.prompt}\n${q.options?.join(' | ')||''}\nĐáp án: ${q.answer} — ${q.explanation}`).join('\n\n')}`);
 }
 const e=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
 const lists=Object.entries(topics).map(([goal])=>`<section><h2>${goal==='COMMUNICATION'?'Giao tiếp B1':goal}</h2><ol>${manifest.lessons.filter(l=>l.goal===goal).map(l=>`<li><a href="${l.slug}.html">${e(l.title)}</a> <small>· ${l.target} · ${e(l.format)} · ${Math.round(l.durationSeconds)} giây</small></li>`).join('')}</ol></section>`).join('');
 await fs.writeFile(path.join(out,'index.html'),`<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DictaLab · 90 bài nghe B1</title><style>body{max-width:1000px;margin:auto;padding:32px 20px;background:#f7f5ef;color:#20342e;font:17px/1.65 'Segoe UI',sans-serif}a{color:#195b47}li{margin:12px 0}small{color:#54635e}h1,h2{font-family:Georgia,serif}</style></head><body><h1>90 bài nghe mới cho người học B1</h1><p>30 bài giao tiếp, 30 bài IELTS mục tiêu 4.0–5.0, 30 bài TOEIC mục tiêu 600–700. Mỗi bài có audio, transcript, câu hỏi và đáp án.</p><p>Đăng nhập để nghe. Giọng AI Mỹ và Anh; mục tiêu luyện tham khảo, chưa hiệu chuẩn điểm thi. IELTS và TOEIC có cấu trúc riêng; tổng điểm TOEIC không được suy ra chỉ từ nghe.</p><p><a href="/catalog">Mở thư viện để luyện chép chính tả</a> · <a href="transcripts.md">Toàn bộ transcript</a></p>${lists}<h2>Nguồn cấu trúc và mức tham khảo</h2><p><a href="https://www.ets.org/content/dam/ets-org/pdfs/toeic/toeic-mapping-cefr-reference.pdf">ETS: CEFR</a> · <a href="https://ielts.org/organisations/ielts-for-organisations/compare-ielts/ielts-and-the-cefr">IELTS: CEFR</a> · <a href="https://ielts.org/take-a-test/test-types/ielts-academic-test/ielts-academic-format-listening">IELTS Listening</a></p></body></html>`,'utf8');
 await fs.writeFile(path.join(out,'transcripts.md'),transcript.join('\n\n')+'\n','utf8');
 console.log(`EXPORT_SQL_WEB_READY: 90 lessons, 180 audio files, ${manifest.lessons.reduce((n,l)=>n+l.questions.length,0)} questions`);
}
async function main(){
 const mode=process.argv[2];await fs.mkdir(root,{recursive:true});
 if(mode==='self-test'){
  const s=specifications();assert.equal(s.length,90);assert.equal(new Set(s.map(x=>x.slug)).size,90);
  for(const goal of Object.keys(topics))assert.equal(s.filter(x=>x.goal===goal).length,30);
  assert(s.filter(x=>x.goal==='IELTS').every(x=>['4.0','5.0'].includes(x.target)));
  assert(s.filter(x=>x.goal==='TOEIC').every(x=>['600','700'].includes(x.target)));
  assert.throws(()=>validateBatch({batchId,calibrated:false,lessons:[]}));
  assert.throws(()=>validateLesson({...s[0],sentences:[],questions:[]}));
  console.log('SELF_TEST_OK: 90 unique specs, B1 targets, strict validation');return;
 }
 if(!process.env.CARTESIA_API_KEY||!process.env.SHOPAIKEY_API_KEY)process.loadEnvFile(path.resolve('.env'));
 if(mode==='generate')await generate();else if(mode==='audio')await audio();else if(mode==='export')await exportBatch();else if(mode==='validate'){const b=JSON.parse(await fs.readFile(path.join(root,'brief.json'),'utf8'));console.log(JSON.stringify(validateBatch(b)))}else throw Error('Expected generate|audio|export|validate|self-test');
}
module.exports={specifications,validateLesson,validateBatch,buildSql};
if(require.main===module)main().catch(e=>{console.error(e.message);process.exitCode=1});
