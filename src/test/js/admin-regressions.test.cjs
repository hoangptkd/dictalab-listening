const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = name => fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js',name),'utf8');
function editor() {
    const values={facts:'Fact A\nFact B\nFact C\nFact D'};
    for(let i=0;i<5;i++) Object.assign(values,{[`q${i}text`]:`Question ${i+1}`,[`q${i}type`]:i===0?'MAIN_IDEA':'DETAIL',
        [`q${i}option0`]:'A',[`q${i}option1`]:'B',[`q${i}option2`]:'C',[`q${i}option3`]:'D',[`q${i}correct`]:'1',[`q${i}explanation`]:'Explanation'});
    const text=source('listening.js');
    const read=vm.runInNewContext(text.slice(text.indexOf('    function readContent('),text.indexOf('    async function admin('))+'\nreadContent');
    return {values,read:()=>read({elements:{namedItem:name=>({value:values[name]||''})}})};
}
test('removing an earlier answer option preserves the selected correct answer',()=>{
    const form=editor(); form.values.q0option0='';
    const result=form.read().questions[0];
    assert.equal(result.options[result.correct],'B');
    assert.equal(result.correct,0);
});
test('an empty selected answer, duplicate options and incomplete mandatory questions are rejected',()=>{
    const missing=editor();missing.values.q0option1=''; assert.throws(missing.read,/lựa chọn trống/);
    const duplicate=editor();duplicate.values.q0option2='b'; assert.throws(duplicate.read,/trùng/);
    const question=editor();question.values.q2text=''; assert.throws(question.read,/Câu 3/);
});
test('submit works for a browser implicit submit button and restores it after failure',async()=>{
    const code=source('account.js');
    const submit=vm.runInNewContext(code.slice(code.indexOf('    const submit ='),code.indexOf('    function showPublic('))+'\nsubmit',{
        status(){},FormData:class{ entries(){return [['q','needle']][Symbol.iterator]();} }
    });
    let handler,called=false;
    const button={disabled:false};
    const form={addEventListener(_event,fn){handler=fn;},querySelector(){return null;},closest(){return {};},hasAttribute(){return false;}};
    submit(form,async data=>{called=true;assert.equal(data.q,'needle');throw Error('network');});
    await handler({preventDefault(){},submitter:button});
    assert.equal(called,true);assert.equal(button.disabled,false);
});

test('submit respects the form disabled state after a successful save', async () => {
    const code = source('account.js');
    const submit = vm.runInNewContext(code.slice(code.indexOf('    const submit ='), code.indexOf('    function showPublic(')) + '\nsubmit', {
        status() {}, FormData: class { entries() { return []; } }
    });
    let handler, saved = false;
    const button = {disabled: false};
    const form = {addEventListener(_event, fn) { handler = fn; }, closest() { return {}; }, hasAttribute() { return false; }};
    submit(form, async () => { saved = true; }, () => saved);
    await handler({preventDefault() {}, submitter: button});
    assert.equal(button.disabled, true);
});
test('saved fields stop being dirty while other edited fields survive a rerender',()=>{
    let allow=false;
    const nodes=()=>({dataset:{adminDirty:'lesson'},isConnected:true,closest(){return null;},fields:[{name:'title',value:'Original'},{name:'description',value:'Original description'},{name:'checked',type:'checkbox',checked:false}],querySelectorAll(){return this.fields;}});
    let forms=[nodes()];
    const document={body:{dataset:{area:'admin'}},querySelectorAll:()=>forms,addEventListener(){}};
    const window={addEventListener(){}};
    vm.runInNewContext(source('admin-ui.js'),{document,window,location:{href:'https://dictalab.test/admin/lessons/1'},confirm:()=>allow,URL,URLSearchParams});
    const ui=window.DictaLabAdmin,root={querySelectorAll:()=>forms};
    ui.track(root);forms[0].fields[0].value='Draft';assert.equal(ui.canLeave(),false);
    forms[0].fields[2].checked=true;
    const saved=ui.capture(root); forms=[nodes()];forms[0].fields[1].value='Fresh description';ui.track(root);ui.restore(root,saved);
    assert.equal(forms[0].fields[0].value,'Draft');assert.equal(ui.dirty(forms[0]),true);
    assert.equal(forms[0].fields[1].value,'Fresh description');assert.equal(forms[0].fields[2].checked,true);
    ui.clean(forms[0]); assert.equal(ui.canLeave(),true);
});
