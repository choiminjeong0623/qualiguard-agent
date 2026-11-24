#!/usr/bin/env python3
"""시연 영상 자막(.srt)을 Typecast 음성으로 읽어 영상에 내레이션을 입힌다.

사용:
  export TYPECAST_API_KEY=...            # 키는 파일에 적지 않는다
  export TYPECAST_VOICE_ID=tc_...
  python3 scripts/narrate_demo.py docs/demo/qualiguard-demo.mp4 docs/demo/qualiguard-demo.srt -o qualiguard-demo-narration.mp4

동작:
  1) 자막 한 줄마다 POST https://api.typecast.ai/v1/text-to-speech (X-API-KEY) → wav 저장
     같은 문장은 다시 부르지 않는다(작업 폴더에 캐시, 크레딧 절약).
  2) 각 음성을 그 자막이 시작하는 시각에 놓는다. 다음 자막 시작 전에 끝나지 않으면
     그 줄만 ffmpeg atempo 로 최대 1.35배까지 빠르게 해 겹치지 않게 한다.
  3) 영상은 다시 인코딩하지 않고(-c:v copy) 음성(AAC)만 붙인다.
옵션: --model ssfm-v30 --tempo 1.0 --emotion normal --keep-dir work_narration --dry-run
"""
import argparse, hashlib, json, os, re, subprocess, sys, urllib.request, urllib.error

API = 'https://api.typecast.ai/v1/text-to-speech'

# 화면 자막은 그대로 두고, 읽을 때만 한국어 발음으로 바꾼다 (영문 약어·기호를 또박또박 읽게)
SAY = [('QualiGuard Agent', '퀄리가드 에이전트'), ('MES·ERP', '엠이에스와 이알피'), ('93~95%', '93에서 95퍼센트'),
       ('25/25', '25건 중 25건'), ('MQTT', '엠큐티티'), ('Kafka', '카프카'), ('QMS', '큐엠에스'), ('LLM', '엘엘엠'),
       ('ML', '엠엘'), ('AI', '에이아이'), ('shot', '샷'), (' · ', ', '), ('(', ', '), (')', '')]


def spoken(text):
    for a, b in SAY:
        text = text.replace(a, b)
    return re.sub(r'\s+', ' ', text).strip()


def parse_srt(path):
    def sec(t):
        h, m, rest = t.split(':'); s, ms = rest.split(',')
        return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000
    out = []
    for block in re.split(r'\n\s*\n', open(path, encoding='utf-8').read().strip()):
        lines = block.strip().splitlines()
        if len(lines) < 3:
            continue
        a, b = [x.strip() for x in lines[1].split('-->')]
        out.append({'start': sec(a), 'end': sec(b), 'text': ' '.join(lines[2:]).strip()})
    return out


def duration(path):
    r = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', path],
                       capture_output=True, text=True, check=True)
    return float(r.stdout.strip())


def fix_wav(path):
    """Typecast 가 스트리밍으로 보낸 wav 는 머리의 길이 칸(RIFF·data)이 0 이나 0xFFFFFFFF 로 올 때가 있다.
    afplay 는 끝까지 재생하지만 ffmpeg 는 길이 0 으로 읽어 영상에 소리가 안 들어간다 → 실제 파일 크기로 고쳐 쓴다."""
    import struct
    b = bytearray(open(path, 'rb').read())
    if b[:4] != b'RIFF' or b[8:12] != b'WAVE':
        return
    pos = 12
    while pos + 8 <= len(b):
        cid, size = bytes(b[pos:pos + 4]), struct.unpack('<I', b[pos + 4:pos + 8])[0]
        if cid == b'data':
            real = len(b) - (pos + 8)
            if size != real:
                b[pos + 4:pos + 8] = struct.pack('<I', real)
                b[4:8] = struct.pack('<I', len(b) - 8)
                open(path, 'wb').write(b)
            return
        pos += 8 + size + (size & 1)


def tts(text, key, voice, model, tempo, emotion, prev_text, next_text, out_path):
    prompt = {'emotion_type': 'smart', 'previous_text': prev_text, 'next_text': next_text} if emotion == 'smart' \
        else {'emotion_type': 'preset', 'emotion_preset': emotion, 'emotion_intensity': 1}
    body = {'voice_id': voice, 'text': text, 'model': model, 'language': 'kor', 'prompt': prompt,
            'output': {'audio_format': 'wav', 'audio_tempo': tempo, 'target_lufs': -16}}
    req = urllib.request.Request(API, data=json.dumps(body).encode('utf-8'), method='POST',
                                 headers={'Content-Type': 'application/json', 'X-API-KEY': key})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            open(out_path, 'wb').write(r.read())
    except urllib.error.HTTPError as e:
        sys.exit(f'Typecast 오류 {e.code}: {e.read().decode("utf-8", "replace")[:300]}  (문장: {text})')
    except urllib.error.URLError as e:
        sys.exit(f'Typecast 에 연결하지 못했습니다: {e.reason}')


def main():
    ap = argparse.ArgumentParser(description='자막을 Typecast 음성으로 읽어 영상에 입힌다')
    ap.add_argument('video'); ap.add_argument('srt')
    ap.add_argument('-o', '--out', required=True)
    ap.add_argument('--model', default='ssfm-v30')
    ap.add_argument('--tempo', type=float, default=1.0, help='Typecast 말 속도 0.5~2.0')
    ap.add_argument('--emotion', default='smart', help='smart(앞뒤 문장 보고 억양) 또는 normal/happy/toneup 등')
    ap.add_argument('--keep-dir', default='work_narration')
    ap.add_argument('--dry-run', action='store_true', help='API 를 부르지 않고 읽을 문장만 보여 준다')
    a = ap.parse_args()

    caps = parse_srt(a.srt)
    total = duration(a.video)
    print(f'자막 {len(caps)}줄, 글자 {sum(len(c["text"]) for c in caps)}자, 영상 {total:.1f}초')
    if a.dry_run:
        for c in caps:
            print(f'{c["start"]:7.2f}  {spoken(c["text"])}')
        return
    key, voice = os.environ.get('TYPECAST_API_KEY'), os.environ.get('TYPECAST_VOICE_ID')
    if not key or not voice:
        sys.exit('TYPECAST_API_KEY 와 TYPECAST_VOICE_ID 환경 변수를 먼저 설정하세요.')
    os.makedirs(a.keep_dir, exist_ok=True)

    clips = []
    for i, c in enumerate(caps):
        say = spoken(c['text'])
        tag = hashlib.sha1(f'{voice}|{a.model}|{a.tempo}|{a.emotion}|{say}'.encode()).hexdigest()[:12]
        wav = os.path.join(a.keep_dir, f'{i + 1:03d}_{tag}.wav')
        if not os.path.exists(wav):
            prev_text = spoken(caps[i - 1]['text']) if i else ''
            next_text = spoken(caps[i + 1]['text']) if i + 1 < len(caps) else ''
            tts(say, key, voice, a.model, a.tempo, a.emotion, prev_text, next_text, wav)
            print(f'  [{i + 1}/{len(caps)}] {say}')
        fix_wav(wav)
        d = duration(wav)
        if d < 0.3:
            sys.exit(f'음성 길이가 {d:.2f}초로 너무 짧습니다: {wav} (Typecast 응답을 확인하세요)')
        slot = (caps[i + 1]['start'] if i + 1 < len(caps) else total) - c['start'] - 0.15
        speed = 1.0
        if d > slot > 0:
            speed = min(1.35, d / slot)
            print(f'    {d:.2f}초 > 자리 {slot:.2f}초 → {speed:.2f}배' + ('  (그래도 조금 겹침)' if d / speed > slot else ''))
        clips.append((wav, c['start'], speed))

    # 1) 영상 길이만큼 무음 바탕(anullsrc)을 깔고, 음성마다 깨끗한 PCM 으로 다시 읽어 시작 시각만큼 밀어(adelay)
    #    필요한 줄만 빠르게(atempo) 한 뒤 바탕 길이에 맞춰 섞는다 → 내레이션 wav 하나 (ffmpeg 버전에 덜 민감)
    track = os.path.join(a.keep_dir, 'narration_track.wav')
    cmd = ['ffmpeg', '-y', '-loglevel', 'error', '-f', 'lavfi', '-t', f'{total:.3f}',
           '-i', 'anullsrc=channel_layout=mono:sample_rate=44100']
    parts = []
    for k, (wav, start, speed) in enumerate(clips):
        cmd += ['-i', wav]
        ms = int(start * 1000)
        tempo = f',atempo={speed:.3f}' if speed > 1.0 else ''
        parts.append(f'[{k + 1}:a]aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=mono{tempo},'
                     f'adelay=delays={ms}:all=1[a{k}]')
    mix = '[0:a]' + ''.join(f'[a{k}]' for k in range(len(clips)))
    fc = ';'.join(parts) + f';{mix}amix=inputs={len(clips) + 1}:duration=first:dropout_transition=0:normalize=0[aout]'
    cmd += ['-filter_complex', fc, '-map', '[aout]', '-c:a', 'pcm_s16le', track]
    subprocess.run(cmd, check=True)
    tdur = duration(track)
    if tdur < total * 0.9:
        sys.exit(f'내레이션 트랙이 {tdur:.2f}초뿐입니다(영상 {total:.1f}초): {track}')

    # 2) 영상은 다시 인코딩하지 않고 내레이션만 AAC 로 붙인다
    cmd = ['ffmpeg', '-y', '-loglevel', 'error', '-i', a.video, '-i', track, '-map', '0:v', '-map', '1:a',
           '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k', '-t', f'{total:.3f}', '-movflags', '+faststart', a.out]
    subprocess.run(cmd, check=True)
    r = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=duration',
                        '-of', 'csv=p=0', a.out], capture_output=True, text=True)
    adur = float(r.stdout.strip() or 0)
    if adur < total * 0.9:
        sys.exit(f'합친 음성 트랙이 {adur:.2f}초뿐입니다(영상 {total:.1f}초). 결과가 잘못됐습니다: {a.out}')
    print(f'완료: {a.out} (음성 {adur:.1f}초)')


if __name__ == '__main__':
    main()
