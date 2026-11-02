import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

cli = shutil.which('agent-browser.cmd') or shutil.which('agent-browser')
session = 'gemini-qa-' + __import__('uuid').uuid4().hex[:10]
def browser(*args, script=None, launch=False):
    if not cli:
        raise RuntimeError('Install agent-browser before running the optional browser smoke check')
    launch_args = []
    chrome = os.environ.get('GEMINI_QA_CHROME', 'C:/Program Files/Google/Chrome/Application/chrome.exe')
    if launch and Path(chrome).is_file():
        launch_args += ['--executable-path', chrome]
    if launch and '--headed' in sys.argv:
        launch_args += ['--headed']
    # On Windows the detached Chrome daemon inherits pipes; files avoid waiting for its EOF.
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        subprocess.run([cli, '--session', session, '--json', *launch_args, *args], input=script,
            text=True, encoding='utf-8', stdout=output, stderr=errors, timeout=60, check=True)
        output.seek(0)
        data = json.loads(output.read().decode('utf-8'))
    if not data['success']:
        raise RuntimeError(data.get('error'))
    return data['data']

script = r"""(async () => {
const results=[];
const root=()=>document.getElementById('admin-gemini-content');
const find=s=>root().querySelector(s);
const text=()=>root().innerText;
const wait=async fn=>{const end=Date.now()+8000;while(!fn()){if(Date.now()>end)throw Error('Timed out: '+fn);await new Promise(r=>setTimeout(r,30));}};
const assert=(value,message)=>{if(!value)throw Error(message);};
const pass=name=>results.push(name);
const qa=body=>fetch('/__qa',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
const field=(name,value,form='#gemini-editor form')=>{const node=find(form).elements.namedItem(name);if(node.type==='checkbox')node.checked=value;else node.value=value;node.dispatchEvent(new Event('input',{bubbles:true}));};
const load=()=>window.DictaLabGemini.show();
const submit=async(selector,expected)=>{find(selector).requestSubmit();await wait(()=>root().getAttribute('aria-busy')!=='true'&&expected());};
const cancel=()=>{window.confirm=()=>true;find('[data-cancel]').click();};
assert(text().includes('Chưa có nguồn Gemini'),'empty state missing');pass('empty state');
await qa({noPool:true});await load();
assert(find('[data-add]').disabled,'add must wait for pool');
field('defaultModel','gemini-3.5-flash-lite','[data-pool]');field('enabled',true,'[data-pool]');
await submit('[data-pool]',()=>text().includes('Đã lưu cấu hình chung.'));pass('create group');
find('[data-add]').click();
field('id','source-a');field('name','Nguồn <img data-pwn src=x>');field('projectId','qa-project');field('apiKey','fake-browser-only-key');field('enabled',true);
field('rpmLimit','5');field('operatingRpm','6');find('#gemini-editor form').requestSubmit();
assert(find('[name=operatingRpm]').getAttribute('aria-invalid')==='true','quota relationship not validated');
field('operatingRpm','4');
await submit('#gemini-editor form',()=>text().includes('Đã lưu.'));
assert(!find('[data-pwn]'),'source name injected HTML');assert(!root().innerHTML.includes('fake-browser-only-key'),'key leaked into response UI');pass('add + validation + XSS + masked key');
find('[data-add]').click();field('id','source-a');field('name','Wrong duplicate');field('projectId','other-project');field('apiKey','fake-duplicate-key');
await submit('#gemini-editor form',()=>find('[data-error]')?.textContent.includes('exists'));
assert(find('[name=apiKey]').value==='','failed save retained key');cancel();pass('duplicate ID rejected');
find('[data-edit="source-a"]').click();assert(find('[name=apiKey]').value===''&&find('[name=apiKey]').type==='password','edit exposed key');
field('name','Nguồn QA đã sửa');await qa({delayWrite:1.2});find('#gemini-editor form').requestSubmit();
await new Promise(r=>setTimeout(r,80));assert([...root().querySelectorAll('button,input,select')].every(n=>n.disabled),'controls remain active during save');
await wait(()=>text().includes('Đã lưu.')&&text().includes('Nguồn QA đã sửa'));pass('edit keeps key + locks concurrent actions');
find('[data-edit="source-a"]').click();field('name','Unsaved');window.confirm=()=>false;find('[data-cancel]').click();
assert(!find('#gemini-editor').hidden,'cancel lost dirty changes');window.confirm=()=>true;find('[data-cancel]').click();assert(find('#gemini-editor').hidden,'cancel did not close editor');pass('dirty form guard');
find('[data-pool]').closest('details').open=true;field('globalRpmLimit','31','[data-pool]');find('[data-edit="source-a"]').click();
assert(find('#gemini-editor').hidden&&text().includes('Lưu hoặc hủy cấu hình chung'),'pool draft was discarded when editing source');
find('[data-reset-pool]').click();find('[data-edit="source-a"]').click();field('name','Draft while editing pool');field('globalRpmLimit','32','[data-pool]');find('[data-pool]').requestSubmit();
assert(text().includes('Lưu hoặc hủy thay đổi của nguồn'),'pool save discarded source draft');find('[data-reset-pool]').click();cancel();pass('source and pool preserve each other drafts');
find('[data-edit="source-a"]').click();field('name','Stale draft');await qa({conflict:true});
await submit('#gemini-editor form',()=>find('[data-error]')?.textContent.includes('changed'));cancel();await load();pass('concurrent revision conflict');
find('[data-edit="source-a"]').click();field('name','Saved despite refresh failure');await qa({failGet:true});
await submit('#gemini-editor form',()=>text().includes('Chưa tải lại được danh sách'));
assert(text().includes('Đã lưu.'),'successful save looks failed when refresh fails');find('[data-retry]').click();
await wait(()=>text().includes('Saved despite refresh failure')&&!!find('[data-edit]'));pass('save succeeds + refresh fails + retry');
find('[data-probe="source-a"]').click();await wait(()=>text().includes('Kết nối thành công'));await qa({probeStatus:429});
find('[data-probe="source-a"]').click();await wait(()=>text().includes('HTTP 429'));await qa({probeStatus:200});pass('connection checks success and 429');
find('[data-pool]').closest('details').open=true;field('defaultModel','gemini-alt','[data-pool]');field('globalRpmLimit','30','[data-pool]');
await submit('[data-pool]',()=>text().includes('Đã lưu cấu hình chung.'));
assert(text().includes('Model mặc định chưa có nguồn đang bật'),'missing default model source warning');
find('[data-pool]').closest('details').open=true;field('defaultModel','gemini-3.5-flash-lite','[data-pool]');field('enabled',false,'[data-pool]');
await submit('[data-pool]',()=>text().includes('Đã lưu cấu hình chung.'));assert(text().includes('Nhóm nguồn đang tắt'),'paused group not visible');pass('default model + quota + pause group');
await qa({usage:true});await load();field('period','month','[data-filters]');await submit('[data-filters]',()=>!!find('meter'));
assert(text().includes('100')&&text().includes('20'),'actual token statistics missing');pass('usage table + monthly chart');
assert(!find('[data-delete]'),'enabled source can be deleted');find('[data-edit="source-a"]').click();field('enabled',false);
await submit('#gemini-editor form',()=>!!find('[data-delete]'));
window.confirm=()=>false;find('[data-delete]').click();assert(!!find('[data-edit]'),'delete cancellation removed source');
window.confirm=()=>true;find('[data-delete]').click();await wait(()=>text().includes('Đã xóa nguồn.'));
assert(!find('[data-edit]')&&!!find('meter')&&text().includes('Xóa nguồn'),'delete lost history or usage');
assert([...find('[name=source]').options].some(o=>o.value==='source-a'),'deleted source cannot be filtered');pass('delete disabled + preserve audit/usage + historical filter');
field('source','source-a','[data-filters]');find('[data-filters]').requestSubmit();await wait(()=>find('[name=source]')?.value==='source-a'&&!!find('meter'));pass('filter deleted source');
await qa({history:true});await load();assert(root().querySelectorAll('.admin-timeline li').length===50,'audit page size wrong');find('[data-older]').click();
await wait(()=>!!find('[data-latest]')&&root().getAttribute('aria-busy')!=='true');assert(root().querySelectorAll('.admin-timeline li').length<50,'older audit page missing');find('[data-latest]').click();
await wait(()=>!!find('[data-older]')&&!find('[data-latest]'));pass('older and newest audit pages');
field('source','','[data-filters]');await submit('[data-filters]',()=>text().includes('source-a, source-b'));pass('operator audit array values');
return results;
})()"""
def run_checks():
    print('Resetting isolated browser fixture', flush=True)
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18089/__qa', data=b'{"reset":true}',headers={'Content-Type':'application/json'}), timeout=10) as response:
        assert response.status==200
    print('Opening isolated Chrome session', flush=True)
    browser('open', 'http://127.0.0.1:18089/admin/gemini', launch=True)
    print('Checking source management workflows', flush=True)
    browser('wait', '--text', 'Thêm nguồn')
    result = browser('eval', '--stdin', script=script)['result']
    print(json.dumps(result, ensure_ascii=False, indent=2))
    out=Path(__file__).resolve().parents[2] / 'target/gemini-audit'
    out.mkdir(exist_ok=True)
    browser('set','viewport','1440','1000')
    browser('screenshot','--full',str(out/'desktop.png'))
    browser('set','viewport','375','900')
    dimensions=browser('eval','--stdin',script='({width:innerWidth,scroll:document.body.scrollWidth})')['result']
    assert dimensions['scroll']<=dimensions['width'],dimensions
    browser('screenshot','--full',str(out/'mobile.png'))
    print('PASS: desktop/mobile width', dimensions)
    errors = browser('errors')['errors']
    assert not errors, errors
    print('PASS: no unhandled browser errors')
