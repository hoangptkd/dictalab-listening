const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/vocabulary.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
function harness(api, {admin=false}={}) {
    const elements = new Map(), inserted = [];
    const element = () => ({dataset:{},disabled:false,classList:{add() {}},setAttribute() {},focus() {this.focused=true;},
        insertAdjacentElement(position,node) {inserted.push(node);},querySelector() {return null;}});
    const listeners = {}, status = {textContent: ''}, input = {value: '', focus() {this.focused = true;}}, preview = {innerHTML:'',replaceChildren() {this.innerHTML='';}};
    const root = {innerHTML: '', addEventListener(name,fn) {listeners[name]=fn;}, setAttribute() {},removeAttribute() {},replaceChildren() {this.innerHTML='';},
        querySelector(selector) {const value=elements.get(selector);return (Array.isArray(value)?value[0]:value) ?? (selector==='#vocab-import-preview'?preview:selector==='[name="answer"]'?input:null);},
        querySelectorAll(selector) {return elements.get(selector) ?? [];}};
    let current = {id: 11}; const calls = [],storage = new Map();
    const window = {DictaLabListeningBridge: {currentUser: () => current,isAdmin: () => admin,
        navigate() {}, async api(url,options) {calls.push({url,options});return api(url,options);}}};
    vm.runInNewContext(source,{window,document:{querySelector: selector => selector==='#vocabulary-content'?root:status,createElement:element},
        AbortController,DOMException,TextDecoder,requestAnimationFrame:fn=>fn(),crypto:{randomUUID:()=>require('node:crypto').randomUUID()},
        FormData: class extends Map {constructor(form) {super(form.values);}},
        sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}});
    return {module:window.DictaLabVocabulary,root,status,calls,input,preview,listeners,elements,inserted,element,setUser:value=>{current=value;}};
}
function session(answer=null,evaluation=null) {
    return {id:'session',mode:'SRS',status:'ACTIVE',items:[{id:'item',position:0,kind:'WORD_RECALL',prompt:'mượn',answer,evaluation,gradedAt:null,assisted:false,
        feedback:answer===null?{}:{term:'borrow',meaning_vi:'mượn',example_en:'<script>unsafe</script>',example_vi:'ví dụ'}}]};
}
test('recall hides answer until attempted and escapes feedback markup',async()=>{
    let state=session(); const h=harness(async url=>url.includes('/decks?')?{items:[]}:state);
    await h.module.show('session'); assert.match(h.root.innerHTML,/mượn/);assert.doesNotMatch(h.root.innerHTML,/borrow/);assert.ok(h.input.focused);
    state=session('x','INCORRECT');await h.module.show('session');
    assert.match(h.root.innerHTML,/borrow/);assert.doesNotMatch(h.root.innerHTML,/<script>/);
    for (const grade of ['AGAIN','HARD','GOOD','EASY']) {
        const button = h.root.innerHTML.match(new RegExp(`<button\\b[^>]*\\bvalue="${grade}"[^>]*>`))?.[0];
        assert.ok(button, `Missing grade button: ${grade}`);
        assert.equal(/\sdisabled(?:\s|>)/.test(button), grade !== 'AGAIN');
    }
    assert.equal(h.status.textContent,'');
});
test('correct recall keeps all grade choices available',async()=>{
    const h=harness(async url=>url.includes('/decks?')?{items:[]}:session('borrow','CORRECT'));
    await h.module.show('session');
    const buttons=[...h.root.innerHTML.matchAll(/<button\b[^>]*\bname="grade"[^>]*>/g)];
    assert.equal(buttons.length,4);
    assert.ok(buttons.every(([button])=>!/\sdisabled(?:\s|>)/.test(button)));
    assert.equal(h.status.textContent,'');
});
test('late responses cannot render after navigation or an account change',async()=>{
    let resolve;const h=harness(()=>new Promise(done=>{resolve=done;}));
    const shown=h.module.show('session');h.module.leave();resolve({items:[]});await shown;
    assert.equal(h.root.innerHTML,'');
    const next=h.module.show('session');h.setUser({id:12});resolve({items:[]});await next;
    assert.equal(h.root.innerHTML,'');
});
test('uncertain network result retries with the same idempotency key',async()=>{
    let count=0;
    const h=harness(async (url,options)=>{
        if(url.includes('/decks?'))return {items:[]};
        if(url.endsWith('/answer')) {if(count++===0)throw new Error('network');return {};}
        return session();
    });
    await h.module.show('session');
    const form={dataset:{vocabForm:'answer'},values:[['answer','borrow']]};
    const event={target:{closest:()=>form},preventDefault(){}};
    h.listeners.submit(event);await tick();h.listeners.submit(event);await tick();
    const writes=h.calls.filter(c=>c.url.endsWith('/answer'));
    assert.equal(writes.length,2);assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
});
test('navigation routes vocabulary actions through the session loader',async()=>{
    const app=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/app.js'),'utf8');
    const start=app.indexOf('    async function navigate(route, params = {}) {');
    const end=app.indexOf('\n    function renderLevelTabs()',start);
    const navigations=[];
    const navigate=vm.runInNewContext(`(${app.slice(start,end).trim()})`,{
        state:{selectedLevel:'A1'},resolveUrlAndNavigate:async(...args)=>navigations.push(args),
        document:{querySelector:()=>{throw new Error('Missing route mapping');}}
    });
    await navigate('vocabulary',{sessionId:'resume-123'});
    assert.equal(navigations[0][0].route,'vocabulary');
    assert.equal(navigations[0][0].params.sessionId,'resume-123');
    assert.equal(navigations[0][1],true);
});

test('changing CSV or failing a new preview removes the previous confirmation',async()=>{
    let invalid=false;
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[]};
        if(url.endsWith('/imports/preview')) {
            if(invalid) throw new Error('Invalid CSV');
            return {id:'import-one',errors:[],count:1,preview:[{term:'bank',meaningVi:'ngân hàng'}]};
        }
        return {deck:{id:'deck',level:'A1',title:'QA'},notes:{items:[],page:1}};
    });
    await h.module.show('session');
    h.listeners.click({target:{closest:()=>({dataset:{vocabAction:'deck',id:'deck'}})}});await tick();
    const form={dataset:{vocabForm:'import'},values:[['file',new Blob(['valid'])]]};
    const event={target:{closest:()=>form},preventDefault(){}};
    h.listeners.submit(event);await tick();await tick();
    assert.match(h.preview.innerHTML,/confirm-import/);
    h.listeners.change({target:{name:'file'}});
    assert.equal(h.preview.innerHTML,'');
    h.listeners.submit(event);await tick();await tick();
    assert.match(h.preview.innerHTML,/confirm-import/);
    invalid=true;h.listeners.submit(event);await tick();await tick();
    assert.equal(h.preview.innerHTML,'');
    assert.equal(h.status.textContent,'Invalid CSV');
});

test('a late CSV preview cannot restore confirmation for a replaced file',async()=>{
    let resolve;
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[]};
        if(url.endsWith('/imports/preview'))return new Promise(done=>{resolve=done;});
        return {deck:{id:'deck',level:'A1',title:'QA'},notes:{items:[],page:1}};
    });
    await h.module.show('session');
    h.listeners.click({target:{closest:()=>({dataset:{vocabAction:'deck',id:'deck'}})}});await tick();
    const form={dataset:{vocabForm:'import'},values:[['file',new Blob(['valid'])]]};
    h.listeners.submit({target:{closest:()=>form},preventDefault(){}});await tick();await tick();
    h.listeners.change({target:{name:'file'}});
    resolve({id:'old-file',errors:[],count:1,preview:[{term:'old',meaningVi:'cũ'}]});await tick();
    assert.equal(h.preview.innerHTML,'');
});

test('invalid UTF-8 clears CSV confirmation and explains how to fix the file',async()=>{
    const h=harness(async url=>url.includes('/decks?')?{items:[]}:{deck:{id:'deck',level:'A1',title:'QA'},notes:{items:[],page:1}});
    await h.module.show('session');
    h.preview.innerHTML='old confirmation';
    const form={dataset:{vocabForm:'import'},values:[['file',new Blob([new Uint8Array([0xff])])]]};
    h.listeners.submit({target:{closest:()=>form},preventDefault(){}});await tick();await tick();
    assert.equal(h.preview.innerHTML,'');
    assert.match(h.status.textContent,/CSV UTF-8/);
    assert.equal(h.calls.filter(c=>c.url.endsWith('/imports/preview')).length,0);
});

const summary = {due:5,new_cards:10,reviewedToday:3,newLimit:10};
const mastery = {level1:2,level2:4,level3:0,level4:1,level5:3};
const notes = [
    {id:'one',term:'borrow',meaning_vi:'mượn',example_en:'I borrow a book.'},
    {id:'two',term:'lend',meaning_vi:'cho mượn',example_en:'I lend a book.'},
    {id:'three',term:'borrow again',meaning_vi:'mượn',example_en:''},
];
function deckHarness(items=notes) {
    return harness(async (url,options)=>{
        if(url.includes('/decks?'))return {items:[],hasMore:false};
        if(url.endsWith('/summary'))return summary;
        if(url.endsWith('/mastery-levels'))return mastery;
        if(url.includes('/decks/deck?'))return {deck:{id:'deck',title:'Test',level:'A1'},notes:{items,page:1,hasMore:false}};
        if(url.endsWith('/sessions') && options.method==='POST')return {id:'saved-session'};
        return session();
    });
}
async function click(h,dataset,target={}) {
    const node=Object.assign(h.element(),target,{dataset});
    h.listeners.click({target:{closest:selector=>selector==='[data-vocab-action]'?node:null}});
    await tick();await tick();
    return node;
}
async function mode(h,name) {
    await h.module.show();
    await click(h,{vocabAction:'deck',id:'deck'});
    await click(h,{vocabAction:'choose-mode'});
    await click(h,{vocabAction:'mode-select',mode:name});
}
test('home shows actual mastery counts and retains scheduled recall and translation',async()=>{
    const h=deckHarness();await h.module.show();
    assert.equal(h.status.textContent,'');
    assert.match(h.root.innerHTML,/4 thẻ/);
    assert.match(h.root.innerHTML,/0 thẻ/);
    assert.match(h.root.innerHTML,/data-mode="CONTEXT_TRANSLATION"/);
    assert.match(h.root.innerHTML,/data-mode="PRACTICE"[^>]*>\s*Luyện thêm/);
});
test('mastery failure displays unavailability without inventing learning statistics',async()=>{
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[]};
        if(url.endsWith('/summary'))return summary;
        throw new Error('mastery unavailable');
    });
    await h.module.show();
    assert.match(h.root.innerHTML,/Chưa tải được mức độ ghi nhớ/);
    assert.doesNotMatch(h.root.innerHTML,/vocab-mastery-count/);
    assert.match(h.root.innerHTML,/data-mode="SRS"/);
});
test('summary failure replaces the skeleton with retry and exposes the error',async()=>{
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[]};
        if(url.endsWith('/mastery-levels'))return mastery;
        throw new Error('summary unavailable');
    });
    await h.module.show();
    assert.equal(h.status.textContent,'summary unavailable');
    assert.match(h.root.innerHTML,/Thử lại/);
    assert.doesNotMatch(h.root.innerHTML,/skel-card/);
});
test('a resolved summary cannot repaint after navigation while mastery is pending',async()=>{
    let resolve;
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[]};
        if(url.endsWith('/summary'))return summary;
        return new Promise(done=>{resolve=done;});
    });
    const shown=h.module.show();await tick();h.module.leave();resolve(mastery);await shown;
    assert.equal(h.root.innerHTML,'');
});
test('both quiz directions escape prompts, deduplicate choices and avoid SRS writes',async()=>{
    for(const direction of ['QUIZ_EN_VI','QUIZ_VI_EN']) {
        const h=deckHarness([...notes,{id:'unsafe',term:'<script>x</script>',meaning_vi:'<img src=x>'}]);
        await mode(h,direction);
        const values=[...h.root.innerHTML.matchAll(/data-value="([^"]*)"/g)].map(m=>m[1]);
        assert.ok(values.length>=2);
        assert.equal(values.length,new Set(values).size);
        assert.doesNotMatch(h.root.innerHTML,/<script>|<img src=x>/);
        assert.match(h.root.innerHTML,new RegExp(`vocab-quiz-prompt" lang="${direction==='QUIZ_EN_VI'?'en':'vi'}"`));
        assert.equal(h.calls.filter(c=>c.options.method==='POST').length,0);
        assert.equal(h.status.textContent,'');
    }
});
test('quiz rejects decks with only one distinct answer',async()=>{
    const h=deckHarness([notes[0],notes[2]]);await mode(h,'QUIZ_EN_VI');
    assert.match(h.root.innerHTML,/ít nhất 2 đáp án khác nhau/);
});
test('quiz scores each answer once and labels the final question correctly',async()=>{
    const h=deckHarness(notes.slice(0,2));await mode(h,'QUIZ_EN_VI');
    for(let question=0;question<2;question++) {
        const correct=h.root.innerHTML.match(/data-correct="([^"]*)"/)[1];
        const target=h.element();target.dataset={vocabAction:'quiz-answer',value:correct,correct};
        h.elements.set('.vocab-quiz-opt',[target]);h.elements.set('.vocab-quiz-grid',h.element());
        const answer=()=>{h.listeners.click({target:{closest:selector=>selector==='[data-vocab-action]'?target:null}});};
        answer();await tick();answer();await tick();
        assert.ok(target.disabled);
        assert.ok(h.inserted.some(n=>n.textContent===(question===0?'Câu tiếp theo →':'Xem kết quả →')));
        await click(h,{vocabAction:'quiz-next'});
    }
    assert.match(h.root.innerHTML,/2\/2/);
});
test('context highlighting escapes HTML and regex characters without corrupting entities',async()=>{
    for(const term of ['amp','C++']) {
        const h=deckHarness([{id:'one',term,meaning_vi:'đúng',example_en:`<unsafe> & ${term}`},
            {id:'two',term:'other',meaning_vi:'sai',example_en:''}]);
        await mode(h,'CONTEXTUAL');
        assert.match(h.root.innerHTML,/&lt;unsafe&gt; &amp; <mark>/);
        assert.ok(h.root.innerHTML.includes(`<mark>${term}</mark>`));
        assert.doesNotMatch(h.root.innerHTML,/<unsafe>/);
        assert.equal(h.status.textContent,'');
    }
});
test('context choices exclude duplicate meanings and announce the last result once',async()=>{
    const h=deckHarness([{...notes[0]}, {...notes[2]}, {...notes[1],example_en:''}]);
    await mode(h,'CONTEXTUAL');
    const meanings=[...h.root.innerHTML.matchAll(/data-note-example-vi="[^"]*">([^<]*)<\/button>/g)].map(m=>m[1]);
    assert.equal(meanings.length,2);assert.equal(new Set(meanings).size,2);
    const target=h.element();target.dataset={vocabAction:'ctx-answer',isCorrect:'true',noteTerm:'borrow',noteMeaning:'mượn'};
    const reveal=h.element();h.elements.set('#ctx-reveal',reveal);h.elements.set('.vocab-ctx-opt',[target]);
    const answer=()=>h.listeners.click({target:{closest:selector=>selector==='[data-vocab-action]'?target:null}});
    answer();await tick();answer();await tick();
    assert.match(reveal.innerHTML,/role="status">Chính xác/);
    assert.match(reveal.innerHTML,/Xem kết quả/);
    await click(h,{vocabAction:'ctx-next'});
    assert.match(h.root.innerHTML,/1\/1/);
});
test('context translation mode starts the existing backend flow for the selected deck',async()=>{
    const h=deckHarness();await mode(h,'CONTEXT_TRANSLATION');
    const write=h.calls.find(c=>c.url.endsWith('/sessions') && c.options.method==='POST');
    assert.equal(write.options.body.mode,'CONTEXT_TRANSLATION');
    assert.equal(write.options.body.deckId,'deck');
});

test('system decks expose editing or copying while only personal decks expose direct study modes',async()=>{
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[],hasMore:false};
        if(url.endsWith('/summary'))return summary;
        if(url.endsWith('/mastery-levels'))return mastery;
        if(url.includes('/decks/deck?'))return {deck:{id:'deck',title:'Test',level:'A1'},notes:{items:notes,page:1,hasMore:false}};
        throw new Error(`Unexpected request: ${url}`);
    },{admin:true});
    await h.module.show();
    for(const scope of ['admin','catalog','mine']) {
        await click(h,{vocabAction:scope});
        await click(h,{vocabAction:'deck',id:'deck'});
        assert.equal(h.status.textContent,'');
        if(scope==='mine') {
            assert.match(h.root.innerHTML,/data-vocab-action="choose-mode"/);
            await click(h,{vocabAction:'choose-mode'});
            assert.match(h.root.innerHTML,/data-mode="SRS"/);
            assert.match(h.root.innerHTML,/data-mode="CONTEXT_TRANSLATION"/);
        } else {
            assert.doesNotMatch(h.root.innerHTML,/data-vocab-action="(?:start|choose-mode|mode-select)"/);
            assert.match(h.root.innerHTML,scope==='admin'?/data-vocab-action="new-note"/:/data-vocab-form="copy"/);
        }
    }
    assert.equal(h.calls.filter(c=>c.options.method==='POST').length,0);
});

test('catalog and published admin decks enroll before choosing a personal study mode',async()=>{
    for(const scope of ['catalog','admin']) {
        const h=harness(async (url,options)=>{
            if(url.includes('/decks?'))return {items:[],hasMore:false};
            if(url.endsWith('/tags'))return [];
            if(url.endsWith('/summary'))return summary;
            if(url.endsWith('/mastery-levels'))return mastery;
            if(url.endsWith('/catalog/decks/source/enroll'))return {id:'personal'};
            if(url.includes('/decks/source?'))return {deck:{id:'source',title:'Public',level:'C2',status:'PUBLISHED'},notes:{items:notes,page:1}};
            if(url.includes('/decks/personal?'))return {deck:{id:'personal',title:'Public',level:'C2'},notes:{items:notes,page:1}};
            if(url.endsWith('/sessions') && options.method==='POST')return {id:'study-session'};
            throw new Error(`Unexpected request: ${url}`);
        },{admin:true});
        await h.module.show();await click(h,{vocabAction:scope});await click(h,{vocabAction:'deck',id:'source'});
        assert.match(h.root.innerHTML,/data-vocab-action="enroll"/);
        await click(h,{vocabAction:'enroll'});
        assert.equal(h.status.textContent,'');
        assert.match(h.root.innerHTML,/data-mode="QUIZ_EN_VI"/);
        assert.equal(h.calls.filter(c=>c.url.endsWith('/sessions')).length,0);
        await click(h,{vocabAction:'mode-select',mode:'SRS'});
        const write=h.calls.find(c=>c.url.endsWith('/sessions'));
        assert.equal(write.options.body.deckId,'personal');
        assert.equal(write.options.body.mode,'SRS');
        assert.equal(h.status.textContent,'');
    }
});

test('dynamic metadata renders safely and deck forms submit custom labels and optional levels',async()=>{
    const h=harness(async url=>{
        if(url.includes('/decks?'))return {items:[],hasMore:false};
        if(url.endsWith('/tags'))return ['Custom <tag>'];
        if(url.endsWith('/summary'))return summary;
        if(url.endsWith('/mastery-levels'))return mastery;
        if(url.endsWith('/decks'))return {id:'custom'};
        if(url.includes('/decks/custom?'))return {deck:{id:'custom',title:'Custom',level:'C2 / chuyên ngành',tags:['Custom <tag>','THPT']},notes:{items:[],page:1}};
        throw new Error(`Unexpected request: ${url}`);
    });
    await h.module.show();await click(h,{vocabAction:'mine'});
    assert.match(h.root.innerHTML,/Nhập theo thang bạn dùng hoặc để trống/);
    assert.doesNotMatch(h.root.innerHTML,/<select name="level">/);
    assert.match(h.root.innerHTML,/Custom &lt;tag&gt;/);
    const form={dataset:{vocabForm:'deck'},values:[['title','Custom'],['description',''],['level','C2 / chuyên ngành'],['tags','THPT\nCustom <tag>\n']]};
    h.listeners.submit({target:{closest:()=>form},preventDefault(){}});await tick();await tick();
    const write=h.calls.find(c=>c.options.method==='POST');
    assert.equal(write.options.body.level,'C2 / chuyên ngành');
    assert.deepEqual(Array.from(write.options.body.tags),['THPT','Custom <tag>']);
    assert.equal(h.status.textContent,'');
    assert.match(h.root.innerHTML,/C2 \/ chuyên ngành/);
    assert.match(h.root.innerHTML,/<li>Custom &lt;tag&gt;<\/li>/);
    assert.doesNotMatch(h.root.innerHTML,/<tag>/);
});

test('deck search encodes custom filters, retains them through pagination and resets on scope change',async()=>{
    const h=deckHarness();await h.module.show();await click(h,{vocabAction:'catalog'});
    const form={dataset:{vocabForm:'deck-filter'},values:[['q','y khoa & C2'],['tag','Chuyên ngành / mới']]};
    h.listeners.submit({target:{closest:()=>form},preventDefault(){}});await tick();await tick();
    await click(h,{vocabAction:'decks-page',page:'2'});
    const filtered=h.calls.filter(c=>c.url.includes('/catalog/decks?')).at(-1).url;
    assert.match(filtered,/page=2/);
    assert.ok(filtered.includes(`q=${encodeURIComponent('y khoa & C2')}`));
    assert.ok(filtered.includes(`tag=${encodeURIComponent('Chuyên ngành / mới')}`));
    assert.match(h.root.innerHTML,/Không có bộ phù hợp/);
    await click(h,{vocabAction:'mine'});
    assert.equal(h.calls.filter(c=>c.url.includes('/decks?')).at(-1).url,'/api/v1/vocabulary/decks?page=1&q=&tag=');
    assert.equal(h.status.textContent,'');
});

test('pending tag suggestions cannot repaint deck results after leaving the page',async()=>{
    let resolveTags;
    const h=harness(async url=>{
        if(url.endsWith('/tags'))return new Promise(resolve=>{resolveTags=resolve;});
        if(url.includes('/decks?'))return {items:[{id:'private',title:'Private vocabulary'}],hasMore:false};
        if(url.endsWith('/summary'))return summary;
        return mastery;
    });
    await h.module.show();
    h.listeners.click({target:{closest:selector=>selector==='[data-vocab-action]'?{dataset:{vocabAction:'mine'}}:null}});
    await tick();h.module.leave();resolveTags(['Private label']);await tick();await tick();
    assert.equal(h.root.innerHTML,'');
});
