"""Create the remaining reviewed B1 audio with installed edge-tts and exact sentence metadata.

One synthesis per speaker per lesson; decoded PCM is sliced at provider sentence boundaries,
then reassembled in original dialogue order. No credentials or paid-plan changes.
"""
import argparse
import asyncio
from collections import defaultdict
import hashlib
import html
import json
from pathlib import Path
import re
import subprocess
import sys
import wave

isolated_library=Path(__file__).resolve().parents[1]/'data/edge-tts-lib'
if isolated_library.is_dir():sys.path.insert(0,str(isolated_library))
import edge_tts

RATE=24000


def digest(data):return hashlib.sha256(data).hexdigest()


def tokens(text):return re.findall(r"[a-z]+(?:'[a-z]+)?",html.unescape(text).lower().replace('’',"'"))


def atomically_save(path,data):
 pending=path.with_name(path.name+'.pending')
 pending.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 pending.replace(path)


async def speaker_audio(root,lesson,speaker,voice):
 entries=[(i,s) for i,s in enumerate(lesson['sentences']) if s['speaker']==speaker]
 text=' '.join(s['text'] for _,s in entries)
 speech_rate='+0%' if lesson['speed']==1.0 else '-3%'
 key=digest(json.dumps({'text':text,'voice':voice,'rate':speech_rate,'boundary':'SentenceBoundary','edgeVersion':edge_tts.__version__},sort_keys=True).encode())
 cache=root/'edge-segments';cache.mkdir(exist_ok=True)
 mp3=cache/(key+'.mp3');metadata=cache/(key+'.jsonl');wav=cache/(key+'.wav')
 if not mp3.exists() or not metadata.exists():
  # Cache is published only after the complete stream and metadata have arrived.
  pending_mp3=cache/(key+'.pending.mp3');pending_metadata=cache/(key+'.pending.jsonl')
  communicate=edge_tts.Communicate(text,voice,rate=speech_rate,boundary='SentenceBoundary',connect_timeout=15,receive_timeout=90)
  await asyncio.wait_for(communicate.save(str(pending_mp3),str(pending_metadata)),timeout=120)
  assert pending_mp3.stat().st_size>1000 and pending_metadata.stat().st_size>100
  pending_mp3.replace(mp3);pending_metadata.replace(metadata)
 if not wav.exists():
  r=subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(mp3),'-ar','24000','-ac','1','-c:a','pcm_s16le',str(wav)],capture_output=True,timeout=30)
  assert r.returncode==0,'Neural audio decoding failed'
 with wave.open(str(wav),'rb') as stream:
  assert (stream.getnchannels(),stream.getsampwidth(),stream.getframerate())==(1,2,RATE)
  pcm=stream.readframes(stream.getnframes())
 boundaries=[json.loads(line) for line in metadata.read_text().splitlines()]
 boundaries=[b for b in boundaries if b['type']=='SentenceBoundary']
 assert len(boundaries)==len(entries),f'Sentence metadata count mismatch for {lesson["slug"]}: {len(boundaries)} != {len(entries)}'
 output={}
 for n,((index,sentence),boundary) in enumerate(zip(entries,boundaries)):
  assert tokens(boundary['text'])==tokens(sentence['text']),'Sentence metadata text mismatch'
  start=boundary['offset']/10000000
  end=(boundary['offset']+boundary['duration'])/10000000
  previous_end=(boundaries[n-1]['offset']+boundaries[n-1]['duration'])/10000000 if n else 0
  next_start=boundaries[n+1]['offset']/10000000 if n+1<len(boundaries) else len(pcm)/(RATE*2)
  # Sentence metadata includes a short trailing margin and can overlap by 50 ms.
  # Split that shared margin at its midpoint; the full batch still passes ASR.
  assert previous_end<=start+0.15 and end<=next_start+0.15,'Overlapping provider sentence boundaries'
  left=max(0,start-0.08,(previous_end+start)/2 if n else 0)
  right=min(len(pcm)/(RATE*2),end+0.12,(end+next_start)/2)
  segment=pcm[round(left*RATE)*2:round(right*RATE)*2]
  assert 0.5<=len(segment)/(RATE*2)<=20,'Unexpected sentence audio duration'
  output[index]=segment
 return output


async def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path)
 args=parser.parse_args();root=args.directory.resolve()
 brief=json.loads((root/'brief.json').read_text(encoding='utf-8'))
 assert brief['batchId']=='b1-expansion-20261015' and len(brief['lessons'])==90
 current=json.loads((root/'manifest.json').read_text(encoding='utf-8')) if (root/'manifest.json').exists() else {'lessons':[]}
 saved={l['slug']:l for l in current['lessons']}
 manifest={**brief,'audioProviders':['Cartesia','Microsoft Edge neural'],'lessons':[]}
 for lesson in brief['lessons']:
  existing=saved.get(lesson['slug'])
  if existing:
   audio=root/existing['audioFile'];assert digest(audio.read_bytes())==existing['sha256']
   assert [s['text'] for s in existing['sentences']]==[s['text'] for s in lesson['sentences']]
   existing.setdefault('audioProvider','Cartesia');existing.setdefault('accent','General American (AI)')
   manifest['lessons'].append(existing);atomically_save(root/'manifest.json',manifest)
   print('AUDIO_CACHED '+existing['slug'],flush=True);continue
  voices={'A':'en-GB-SoniaNeural','B':'en-GB-RyanNeural'} if lesson['goal']=='IELTS' else {'A':'en-US-JennyNeural','B':'en-US-GuyNeural'}
  # Two streams maximum; a different lesson begins only after this one is fully cached.
  results=await asyncio.gather(*(speaker_audio(root,lesson,speaker,voices[speaker]) for speaker in sorted({s['speaker'] for s in lesson['sentences']})),return_exceptions=True)
  for result in results:
   if isinstance(result,Exception):raise result
  segments={i:pcm for result in results for i,pcm in result.items()}
  assert len(segments)==len(lesson['sentences'])
  chunks=[];aligned=[];frames=0
  for i,s in enumerate(lesson['sentences']):
   pcm=segments[i];start=frames/RATE;chunks.append(pcm);frames+=len(pcm)//2
   aligned.append({**s,'position':i+1,'audioStartSeconds':start,'audioEndSeconds':frames/RATE})
   if i+1<len(segments):
    pause=5 if lesson['sentences'][i+1].get('role')=='section-start' or lesson['oralOnly'] and i%4==3 else 0.7 if lesson['oralOnly'] and i%4==0 else 0.2
    gap=bytes(round(pause*RATE)*2);chunks.append(gap);frames+=len(gap)//2
  audio=root/(lesson['slug']+'.wav')
  with wave.open(str(audio),'wb') as stream:
   stream.setnchannels(1);stream.setsampwidth(2);stream.setframerate(RATE);stream.writeframes(b''.join(chunks))
  ready={**lesson,'audioFile':audio.name,'sha256':digest(audio.read_bytes()),'durationSeconds':frames/RATE,'sentences':aligned,
   'audioProvider':'Microsoft Edge neural','voiceNames':voices,'accent':'British English (AI)' if lesson['goal']=='IELTS' else 'General American (AI)'}
  manifest['lessons'].append(ready);atomically_save(root/'manifest.json',manifest)
  print(f'NEURAL_AUDIO_READY {len(manifest["lessons"])}/90 {lesson["slug"]} {frames/RATE:.2f}s',flush=True)
 assert len(manifest['lessons'])==90
 print('ALL_90_AUDIO_READY',flush=True)


if __name__=='__main__':
 try:asyncio.run(main())
 except Exception as error:
  print('NEURAL_GENERATION_FAILED: '+(str(error) if isinstance(error,AssertionError) else type(error).__name__)+'; provider connection details suppressed',file=sys.stderr)
  sys.exit(1)
