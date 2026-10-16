const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const script=fs.readFileSync(path.join(__dirname,'../../../scripts/restore-drill-windows-vps.ps1'),'utf8');

test('release rejects unverified S3 dependencies before service stop and checks the restored snapshot before smoke',()=>{
    const updater=fs.readFileSync(path.join(__dirname,'../../../scripts/update-windows-vps.ps1'),'utf8');
    const preflight=updater.indexOf('& $restoreScript -CheckS3Only');
    assert.ok(preflight>=0,'S3 preflight is missing');
    assert.ok(preflight<updater.indexOf('\n    Stop-AppService'));
    const snapshot=script.indexOf('Assert-S3RecoverySupported -Psql $psql -DatabaseName $restoreName');
    assert.ok(snapshot>=0,'Restored S3 dependency gate is missing');
    assert.ok(snapshot<script.indexOf('foreach($testedArtifact'));
    assert.ok(script.includes('RESTORE_EXTERNAL_S3_VERIFIED=FALSE'));
});

test('actual PowerShell S3 gate rejects nonzero exit and missing or unexpected success markers without leaking output',()=>{
    const source=path.join(__dirname,'../../../scripts/restore-drill-windows-vps.ps1').replaceAll("'","''");
    const command=`$ErrorActionPreference='Stop'; $errors=$null; $tokens=$null; $ast=[Management.Automation.Language.Parser]::ParseFile('${source}',[ref]$tokens,[ref]$errors); if($errors.Count){throw 'Parse failed'};
      foreach($name in @('Get-S3RecoveryGateSql','Assert-S3RecoverySupported')) { $function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true); if(-not $function){throw 'S3 gate missing'}; Invoke-Expression $function.Extent.Text }
      function Test-Psql { $global:LASTEXITCODE=if($script:mode -eq 'exit-error'){1}else{0}; if($script:mode -in @('ok','exit-error')){'RESTORE_S3_DEPENDENCIES_NONE'} elseif($script:mode -eq 'unexpected'){'private-bucket-credential'} }
      $results=@{}; foreach($script:mode in @('ok','exit-error','empty','unexpected')) { try { Assert-S3RecoverySupported -Psql 'Test-Psql' -DatabaseName 'fixture' -DatabaseUser 'fixture' | Out-Null; $results[$script:mode]=$true } catch { if($_.Exception.Message -like '*private-bucket-credential*'){throw 'Output leaked'}; $results[$script:mode]=$false } }; $results | ConvertTo-Json -Compress`;
    const result=spawnSync(process.platform==='win32'?'powershell.exe':'pwsh',['-NoProfile','-NonInteractive','-Command',command],{encoding:'utf8',timeout:20000});
    assert.equal(result.status,0,result.stderr);
    assert.deepEqual(JSON.parse(result.stdout.replace(/^\uFEFF/,'')),{ok:true,'exit-error':false,empty:false,unexpected:false});
    assert.ok(!result.stdout.includes('private-bucket-credential'));
});

test('restore smoke disables destructive remote work even when production flags are inherited',()=>{
    const launch=script.slice(script.indexOf('$smoke=Start-Process'),script.indexOf('$null=$smoke.Handle'));
    for(const flag of ['--dictalab.media.s3.enabled=false','--dictalab.media.s3.cleanup-enabled=false',
        '--dictalab.ai.queue.enabled=false','--dictalab.listening.queue.enabled=false',
        '--dictalab.shopaikey.api-key=','--dictalab.conversation.cartesia-api-key=',
        '--dictalab.auth.previous-signing-key=','--dictalab.mail-from=']) assert.ok(launch.includes(flag),flag);
    assert.ok(script.includes('$env:JWT_SIGNING_KEY=[Convert]::ToBase64String($jwtBytes)'));
});

test('restore requires a verified empty dedicated endpoint and checks it before stopping production',()=>{
    assert.ok(script.includes('restore-redis-url.xml'));
    assert.ok(script.includes('RedisIsolationCheck --restore'));
    assert.ok(!script.includes("$redisUri.Path='/15'"));
    assert.ok(script.includes('$env:SPRING_APPLICATION_JSON=New-RestoreSmokeConfiguration'));
    assert.ok(script.includes("'SPRING_APPLICATION_JSON'"));
    const updater=fs.readFileSync(path.join(__dirname,'../../../scripts/update-windows-vps.ps1'),'utf8');
    assert.ok(updater.indexOf('& $restoreScript -CheckRedisOnly')<updater.indexOf('\n    Stop-AppService'));
    for(const filename of ['scripts/deploy-to-vps.ps1']) {
        const deploy=fs.readFileSync(path.join(__dirname,'../../..',filename),'utf8');
        assert.ok(deploy.includes('RedisIsolationCheck.java'),filename);
        assert.ok(deploy.includes('RedisIsolationCheck.class'),filename);
    }
});

test('Linux CI deploy uses the tested release helper, not the Windows restore workflow',()=>{
    const workflow=fs.readFileSync(path.join(__dirname,'../../../.github/workflows/deploy.yml'),'utf8');
    const deploy=workflow.slice(workflow.indexOf('  deploy:'));
    assert.ok(deploy.includes('needs: [test-build, postgres-tests, reliability]'));
    assert.ok(deploy.includes('scripts/linux/release.py'));
    assert.ok(deploy.includes('sudo -n python3'));
    assert.ok(deploy.includes('sha256sum dictalab-new.jar'));
    assert.ok(!deploy.includes('update-windows-vps.ps1'));
    const release=fs.readFileSync(path.join(__dirname,'../../../scripts/linux/release.py'),'utf8');
    assert.ok(release.includes("'--quiescent'"));
    assert.ok(release.indexOf("'--quiescent'")<release.indexOf('replace(candidate, APP)'));
    assert.ok(release.includes("replace(backup / 'dictalab.jar', APP)"));
    assert.ok(release.includes('wait_ready()'));
});

test('actual PowerShell configuration isolates DB Redis keys and remote work without putting secrets in arguments',()=>{
    const source=path.join(__dirname,'../../../scripts/restore-drill-windows-vps.ps1').replaceAll("'","''");
    const command=`$errors=$null; $tokens=$null; $ast=[Management.Automation.Language.Parser]::ParseFile('${source}',[ref]$tokens,[ref]$errors); if($errors.Count){throw 'Parse failed'}; $function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'New-RestoreSmokeConfiguration'},$true); if(-not $function){throw 'Missing isolation builder'}; Invoke-Expression $function.Extent.Text; New-RestoreSmokeConfiguration -DatabaseUrl 'jdbc:postgresql://127.0.0.1:5432/dictalab_restore_test' -DatabaseUser 'fixture' -DatabasePassword 'test-only-db-secret' -RedisUrl 'redis://127.0.0.1:16480/0' -SigningKey 'test-only-new-key' -MediaRoot 'C:/fixture/media'`;
    const result=spawnSync(process.platform==='win32'?'powershell.exe':'pwsh',['-NoProfile','-NonInteractive','-Command',command],{encoding:'utf8',timeout:20000});
    assert.equal(result.status,0,result.stderr);
    const config=JSON.parse(result.stdout.replace(/^\uFEFF/,''));
    assert.equal(config.spring.datasource.url,'jdbc:postgresql://127.0.0.1:5432/dictalab_restore_test');
    assert.equal(config.spring.datasource.password,'test-only-db-secret');
    assert.equal(config.spring.datasource.hikari['jdbc-url'],config.spring.datasource.url);
    assert.equal(config.spring.datasource.hikari.username,config.spring.datasource.username);
    assert.equal(config.spring.datasource.hikari.password,config.spring.datasource.password);
    assert.equal(config.spring.datasource.hikari['maximum-pool-size'],3);
    assert.equal(config.spring.data.redis.url,'redis://127.0.0.1:16480/0');
    assert.equal(config.spring.mail.host,'');
    assert.equal(config.spring.profiles.active,'restore-smoke');
    assert.equal(config.spring.config.location,'classpath:/application.yml');
    assert.equal(config.spring.config['additional-location'],'');
    assert.equal(config.spring.config.import,'');
    assert.equal(config.dictalab.auth['signing-key'],'test-only-new-key');
    assert.equal(config.dictalab.auth['previous-signing-key'],'');
    assert.equal(config.dictalab.auth['key-id'],'restore');
    assert.equal(config.dictalab.media.root,'C:/fixture/media');
    assert.equal(config.dictalab.media.s3.enabled,false);
    assert.equal(config.dictalab.media.s3['cleanup-enabled'],false);
    assert.equal(config.dictalab.ai.queue.enabled,false);
    assert.equal(config.dictalab.listening.queue.enabled,false);
    assert.equal(config.dictalab['mail-from'],'');
    assert.equal(config.management.tracing.export.otlp.enabled,false);
    assert.equal(config.management.logging.export.otlp.enabled,false);
    assert.equal(config.management.otlp.metrics.export.enabled,false);
    for(const dependency of ['rabbit','mail','redis']) assert.equal(config.management.health[dependency].enabled,false);
    assert.equal(config.server.address,'127.0.0.1');
});
