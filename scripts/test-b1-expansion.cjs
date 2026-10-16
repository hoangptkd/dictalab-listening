"use strict";
const assert=require('node:assert/strict'),{specifications,validateLesson,buildSql}=require('./create-b1-expansion.cjs');
const specs=specifications();
function fixture(spec){
 const sentences=Array.from({length:16},(_,i)=>({speaker:(spec.goal==='IELTS'&&[2,4].includes(spec.part))||(spec.goal==='TOEIC'&&spec.part===4)?'A':i%2?'B':'A',
  text:`We will review the ${['first','second','third','fourth','fifth','sixth','seventh','eighth','ninth','tenth','eleventh','twelfth','thirteenth','fourteenth','fifteenth','sixteenth'][i]} option before making a practical decision today.`,audioStartSeconds:i*2,audioEndSeconds:i*2+1.9}));
 sentences[0].text='This lesson considers '+spec.context.toLowerCase().replace(/[.!?:\d]/g,'').split(' ').slice(0,16).join(' ')+'.';
 let questions=Array.from({length:6},(_,i)=>({prompt:`What will happen in section ${i+1}?`,options:['Review an option.','Cancel the whole project.','Order some new furniture.'],answer:'A',evidenceSentence:i+1,evidence:'review',explanation:'Thông tin nêu rõ trong đoạn.'}));
 if(spec.goal==='IELTS'&&[1,2,4].includes(spec.part))questions=questions.map(q=>({...q,prompt:'Activity: ______.',options:undefined,answer:'review'}));
 if(spec.goal==='TOEIC'&&spec.part===2){
  for(let i=0;i<4;i++){
   sentences[i*4].role='question';
   for(let j=1;j<4;j++){sentences[i*4+j].speaker='B';sentences[i*4+j].role='choice'}
  }
  questions=Array.from({length:4},(_,i)=>({prompt:`Choose the response for question ${i+1}.`,options:sentences.slice(i*4+1,i*4+4).map(s=>s.text),answer:'B',evidenceSentence:i*4+1,evidence:'review',explanation:'Chọn phản hồi phù hợp.'}));
 }
 if(spec.goal==='TOEIC'&&[3,4].includes(spec.part)){sentences[8].role='section-start';questions.forEach((q,i)=>q.evidenceSentence=i<3?i+2:i+6)}
 questions[0].evidence=sentences[questions[0].evidenceSentence-1].text.split(' ').slice(0,4).join(' ');
 if(spec.goal==='IELTS'&&[1,2,4].includes(spec.part))questions[0].answer='This';
 return {...spec,instructions:'Nghe và trả lời.',sentences,questions,audioFile:spec.slug+'.wav',sha256:'a'.repeat(64),durationSeconds:32};
}
const lessons=specs.map(fixture),manifest={batchId:'b1-expansion-20261015',calibrated:false,lessons};
const sql=buildSql(manifest);
assert.equal((sql.match(/INSERT INTO lessons\(/g)||[]).length,90);
assert.equal((sql.match(/INSERT INTO lesson_sentences\(/g)||[]).length,1440);
assert(sql.includes('S3 verification required')&&sql.endsWith('COMMIT;\n'));
assert(!sql.includes('DELETE FROM')&&!sql.includes('UPDATE lessons')&&!sql.includes('ON CONFLICT'));
const oral=fixture(specs.find(s=>s.goal==='TOEIC'&&s.part===2));validateLesson(oral);
oral.questions[0].options[0]='Unspoken answer.';assert.throws(()=>validateLesson(oral));
const bad=fixture(specs[0]);bad.questions[1].evidenceSentence=0;assert.throws(()=>validateLesson(bad));
const overlap=structuredClone(manifest);overlap.lessons[0].sentences[1].audioStartSeconds=0;assert.throws(()=>buildSql(overlap));
console.log('B1_EXPANSION_TEST_OK: 90 inserts, S3 gate, spoken choices, evidence, timing, no old-lesson edits');
