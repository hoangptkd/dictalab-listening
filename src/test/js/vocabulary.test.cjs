const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/vocabulary.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
function harness(api) {
    const listeners = {}, status = {textContent: ''}, input = {value: '', focus() {this.focused = true;}};
    const root = {innerHTML: '', addEventListener(name,fn) {listeners[name]=fn;}, setAttribute() {},removeAttribute() {},replaceChildren() {this.innerHTML='';},querySelector() {return input;}};
    let current = {id: 11}; const calls = [],storage = new Map();
    const window = {DictaLabListeningBridge: {currentUser: () => current,isAdmin: () => false,
        navigate() {}, async api(url,options) {calls.push({url,options});return api(url,options);}}};
    vm.runInNewContext(source,{window,document:{querySelector: selector => selector==='#vocabulary-content'?root:status},
        AbortController,DOMException,crypto:{randomUUID:()=>require('node:crypto').randomUUID()},
        FormData: class extends Map {constructor(form) {super(form.values);}},
        sessionStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)}});
    return {module:window.DictaLabVocabulary,root,status,calls,input,listeners,setUser:value=>{current=value;}};
}
function session(answer=null,evaluation=null) {
    return {id:'session',mode:'SRS',status:'ACTIVE',items:[{id:'item',position:0,kind:'WORD_RECALL',prompt:'mượn',answer,evaluation,gradedAt:null,assisted:false,
        feedback:answer===null?{}:{term:'borrow',meaning_vi:'mượn',example_en:'<script>unsafe</script>',example_vi:'ví dụ'}}]};
}
test('recall hides answer until attempted and escapes feedback markup',async()=>{
    let state=session(); const h=harness(async url=>url.includes('/decks?')?{items:[]}:state);
    await h.module.show('session'); assert.match(h.root.innerHTML,/mượn/);assert.doesNotMatch(h.root.innerHTML,/borrow/);assert.ok(h.input.focused);
    state=session('x','INCORRECT');await h.module.show('session');
    assert.match(h.root.innerHTML,/borrow/);assert.doesNotMatch(h.root.innerHTML,/<script>/);assert.match(h.root.innerHTML,/value="GOOD" disabled/);
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
