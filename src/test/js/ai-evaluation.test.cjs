const {test}=require('node:test');
const assert=require('node:assert/strict');
const {esc,rate,trendChart,criteriaChart,regressionChart,runnableVersion}=require('../../main/resources/static/js/ai-evaluation.js');

test('only current scenario snapshots can start a new evaluation; old evidence stays readable',()=>{
    const catalog={available:true,backendHash:'build',releases:[{promptHash:'current'}]};
    const version={pipeline:'scenario',pythonBackendHash:'build',promptHash:'current'};
    assert.equal(runnableVersion(version,catalog),true);
    for(const v of [{...version,pipeline:'java'},{...version,promptHash:'old'},{...version,pythonBackendHash:'old'}]) {
        assert.equal(runnableVersion(v,catalog),false);
    }
    assert.equal(runnableVersion(version,{...catalog,available:false}),false);
    assert.equal(runnableVersion(version,undefined),false);
});

test('chart labels escape untrusted text and missing rates stay unknown',()=>{
    assert.equal(esc('<img src=x onerror="x">'), '&lt;img src=x onerror=&quot;x&quot;&gt;');
    assert.equal(rate(0,0),null);assert.equal(rate(0,3),0);assert.equal(rate(3,3),1);
    const html=trendChart([{label:'<script>x</script>',status:'COMPLETED',metrics:{planned:3,beforeEvaluated:3,afterEvaluated:3,beforePassed:0,afterPassed:3,beforeRate:0,afterRate:1}}]);
    assert.ok(!html.includes('<script>'));assert.ok(html.includes('100%'));assert.ok(html.includes('0%'));assert.equal((html.match(/class="point"/g)||[]).length,2);
});
test('incomplete runs break trend lines and unknown criteria have no filled bars',()=>{
    const run={label:'v1',status:'COMPLETED',metrics:{planned:3,beforeEvaluated:3,afterEvaluated:3,beforeRate:.5,afterRate:.8}};
    const chart=trendChart([run,{...run,status:'STOPPED'},run]);
    assert.ok(!chart.includes('<polyline'));
    assert.ok(!criteriaChart({criteria:{}}).includes('fill="var(--before)"'));
    assert.ok(criteriaChart({criteria:{answerable:{beforePassed:0,beforeEvaluated:2,afterPassed:2,afterEvaluated:2}}}).includes('100%'));
});
test('empty charts give useful empty states and regression counts have accessible labels',()=>{
    assert.ok(trendChart([]).includes('Chưa có dữ liệu'));
    assert.ok(regressionChart({improved:0,unchanged:0,regressed:0,unresolved:0}).includes('Chưa có cặp test'));
    const chart=regressionChart({improved:2,unchanged:3,regressed:1,unresolved:1});
    assert.ok(chart.includes('role="img"'));assert.ok(!chart.includes('NaN'));
});
