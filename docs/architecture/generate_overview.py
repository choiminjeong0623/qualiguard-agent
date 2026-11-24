import sys
from html import escape as E
W,H=1700,1060
FONT="Pretendard, 'Apple SD Gothic Neo', 'Noto Sans KR', 'Malgun Gothic', 'WenQuanYi Zen Hei', sans-serif"
MONO="'JetBrains Mono', Menlo, Consolas, 'WenQuanYi Zen Hei', monospace"
C={ # fill, stroke, text
 'edge':('#fff7ed','#ea580c','#7c2d12'),
 'app':('#eef2ff','#4f46e5','#1e1b4b'),
 'llm':('#f5f3ff','#7c3aed','#3b0764'),
 'rule':('#eff6ff','#2563eb','#172554'),
 'topic':('#ecfeff','#0891b2','#164e63'),
 'db':('#f0fdf4','#16a34a','#14532d'),
 'human':('#fdf2f8','#db2777','#831843'),
 'ext':('#f8fafc','#64748b','#0f172a'),
}
o=[]
def node(x,y,w,h,kind,title,lines=(),dashed=False,rx=10,badge=None):
    f,s,t=C[kind]
    da=' stroke-dasharray="5 4"' if dashed else ''
    o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{f}" stroke="{s}" stroke-width="1.6"{da}/>')
    n=1+len(lines); lh=17
    ty=y+h/2-(n-1)*lh/2+5
    o.append(f'<text x="{x+w/2}" y="{ty}" text-anchor="middle" font-size="14.5" font-weight="700" fill="{t}">{E(title)}</text>')
    for i,l in enumerate(lines):
        o.append(f'<text x="{x+w/2}" y="{ty+(i+1)*lh}" text-anchor="middle" font-size="11.8" fill="{t}" opacity=".85">{E(l)}</text>')
    if badge:
        bw=len(badge)*7+16
        o.append(f'<rect x="{x+w-bw-6}" y="{y-9}" width="{bw}" height="18" rx="9" fill="{s}"/>')
        o.append(f'<text x="{x+w-bw/2-6}" y="{y+4}" text-anchor="middle" font-size="10.5" font-weight="700" fill="#fff">{E(badge)}</text>')
def topic(x,y,w,name,h=40):
    f,s,t=C['topic']
    o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h/2}" fill="{f}" stroke="{s}" stroke-width="1.6"/>')
    o.append(f'<text x="{x+w/2}" y="{y+h/2+4.5}" text-anchor="middle" font-size="12.5" font-family="{MONO}" fill="{t}">{E(name)}</text>')
def arrow(d,color='#475569',dashed=False,both=False,w=1.6):
    da=' stroke-dasharray="6 4"' if dashed else ''
    ms=' marker-start="url(#as)"' if both else ''
    o.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{w}"{da} marker-end="url(#a)"{ms}/>')
def label(x,y,txt,anchor='middle',size=11.5,color='#334155',mono=False,bg=True,weight=400):
    fam=f' font-family="{MONO}"' if mono else ''
    if bg:
        wdt=sum(7.2 if ord(c)<128 else 12 for c in txt)*size/11.5+10
        x0={'middle':x-wdt/2,'start':x-5,'end':x-wdt+5}[anchor]
        o.append(f'<rect x="{x0}" y="{y-size}" width="{wdt}" height="{size+7}" rx="4" fill="#ffffff" opacity=".92"/>')
    o.append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="{size}"{fam} fill="{color}" font-weight="{weight}">{E(txt)}</text>')
def lane(y,h,title,sub):
    o.append(f'<rect x="20" y="{y}" width="1430" height="{h}" rx="14" fill="#f8fafc" stroke="#e2e8f0"/>')
    o.append(f'<text x="40" y="{y+28}" font-size="15" font-weight="800" fill="#0f172a">{E(title)}</text>')
    o.append(f'<text x="40" y="{y+47}" font-size="11.5" fill="#64748b">{E(sub)}</text>')

# header
o.append(f'<text x="24" y="44" font-size="26" font-weight="800" fill="#0f172a">QualiGuard Agent · 시스템 설계도</text>')
o.append(f'<text x="24" y="70" font-size="13.5" fill="#475569">사출성형 공정 이상 탐지 → 역할 분리형 멀티 에이전트 원인 분석 → 사람 승인 → MES/ERP 연계  ·  Java 21 · Spring Boot 4 · Spring AI 2 (Gemini) · Kafka · MQTT · SQL Server</text>')

# lanes
lane(92,215,'① 현장 수집 · 실시간 이상 탐지','현장은 MQTT, 사내 연계는 Kafka')
lane(322,345,'② 멀티 에이전트 원인 분석','')
o.append('<text x="265" y="350" font-size="11.5" fill="#64748b">에이전트 사이는 Kafka agent.* 7토픽 (키 = 사건 ID, 에이전트마다 소비자 그룹)</text>')
lane(682,240,'③ 사람 승인 · MES/ERP 연계','최종 판단은 사람 · 승인과 이벤트는 아웃박스로 한 트랜잭션')

# lane1
Y=170
node(40,Y,175,78,'edge','센서 시뮬레이터',['Python · 사출성형기 2대','센서 7종 + 비전, 고장 4종'])
node(250,Y,165,78,'edge','Mosquitto',['MQTT 브로커','장비별 계정 · 토픽 ACL'])
node(450,Y,185,78,'app','MQTT→Kafka 브리지',['형식 검증 · 중복 제거','지속 세션 · 수동 ack'])
topic(668,Y+19,120,'sensor.raw')
node(822,Y,205,78,'app','SPC 이상 탐지',['Kafka Streams · exactly-once','관리도 4종 · 설비별 상태 저장소'])
topic(1060,Y-6,160,'sensor.clean')
topic(1060,Y+44,160,'quality.anomaly')
node(1252,Y,170,78,'app','DB 적재',['QualityStoreWriter','read_committed · 중복 제외'])
topic(472,Y+90,140,'quality.dlq',h=30)
arrow(f'M215 {Y+39} H248'); label(232,Y+30,'QoS 1',size=10.5)
arrow(f'M415 {Y+39} H448')
arrow(f'M635 {Y+39} H666')
arrow(f'M542 {Y+78} V{Y+88}',dashed=True,color='#dc2626')
label(620,Y+110,'형식 오류',anchor='start',size=10.5,color='#dc2626',bg=False)
arrow(f'M788 {Y+39} H820')
arrow(f'M1027 {Y+30} C1045 {Y+30} 1042 {Y+14} 1058 {Y+14}')
arrow(f'M1027 {Y+48} C1045 {Y+48} 1042 {Y+64} 1058 {Y+64}')
arrow(f'M1220 {Y+14} C1236 {Y+14} 1234 {Y+30} 1250 {Y+30}')
arrow(f'M1220 {Y+64} C1236 {Y+64} 1234 {Y+48} 1250 {Y+48}')
arrow(f'M1422 {Y+39} H1478')

# lane2
arrow('M1478 378 H125 V458',dashed=True,color='#16a34a')
label(330,382,'미처리 경보 조회 (5초 폴링 · 분석 API)',size=11,color='#15803d')
node(40,460,170,84,'app','스케줄러 · 분석 API',['미처리 경보 → 사건','진행 중이면 대기'])
node(245,460,170,84,'rule','분류 에이전트',['규칙 (LLM 호출 없음)','심각도 · 유형 판정'],badge='Triage')
f,s,t=C['rule']
cx,cy=560,502
o.append(f'<polygon points="{cx-95},{cy} {cx-72},{cy-46} {cx+72},{cy-46} {cx+95},{cy} {cx+72},{cy+46} {cx-72},{cy+46}" fill="{f}" stroke="{s}" stroke-width="2"/>')
o.append(f'<text x="{cx}" y="{cy-8}" text-anchor="middle" font-size="15" font-weight="800" fill="{t}">오케스트레이터</text>')
o.append(f'<text x="{cx}" y="{cy+10}" text-anchor="middle" font-size="11.8" fill="{t}">사건 상태 기계</text>')
o.append(f'<text x="{cx}" y="{cy+26}" text-anchor="middle" font-size="11.8" fill="{t}">판단 없이 다음 단계로 넘김</text>')
arrow('M210 502 H243'); arrow('M415 502 H463')
label(439,494,'triaged',size=10,mono=True,bg=False,color='#0e7490')
AX=820
node(AX,388,215,68,'llm','원인 분석 에이전트',['ML 먼저 · 확실하면 LLM 생략','애매하면 Gemini + 도구 5개'],badge='ML → LLM')
node(AX,468,215,68,'rule','검증 에이전트',['규칙 5종 · 근거/모순 점검','약하면 되돌림 (최대 1회)'],badge='Rule')
node(AX,548,215,68,'llm','보고서 작성 에이전트',['로컬 Ollama qwen2.5 1.5B','실패 시 근거 표 · 클라우드 0회'],badge='Local LLM')
arrow('M632 480 C700 470 740 424 818 422',both=True,color='#7c3aed')
arrow('M655 502 H818',both=True,color='#2563eb')
arrow('M632 524 C700 534 740 580 818 582',both=True,color='#7c3aed')
label(768,452,'analysis.*',size=10,mono=True,color='#6d28d9')
label(737,495,'verification.*',size=10,mono=True,color='#1d4ed8')
label(768,560,'report.*',size=10,mono=True,color='#6d28d9')
arrow('M560 456 C560 412 580 402 620 402 H780 C800 402 810 404 818 408',dashed=True,color='#dc2626')
label(612,422,'WEAK → 지적 붙여 재분석',anchor='start',size=10.5,color='#b91c1c',bg=False)
node(1095,396,165,46,'ext','Google Gemini',['클라우드 LLM'],dashed=True)
node(1095,560,165,46,'ext','Ollama',['로컬 소형 LLM (기밀 문장)'],dashed=True)
arrow('M1035 419 H1093',both=True,color='#64748b')
arrow('M1035 582 H1093',both=True,color='#64748b')
arrow('M1035 449 H1478',color='#16a34a')
label(1290,465,'조회 시점(as-of) 고정 · 미래 정보 차단',size=10.8,color='#15803d')
arrow('M1035 502 H1478',color='#16a34a')
label(1260,506,'도구 호출 기록 · 실제 불량률 대조',size=10.8,color='#15803d')
arrow('M560 548 V642 H1478',color='#16a34a',w=2)
label(1000,646,'agent_incident · agent_analysis · 부적합 초안(DRAFT) 저장 → AWAITING_APPROVAL',size=11,color='#15803d',weight=700)
label(40,590,'결과: 초안 → 승인 대기 · 관찰/센서 점검 → CLOSED · 실패 → FAILED',anchor='start',size=10.8,color='#475569',bg=False)
label(40,608,'transport=direct: Kafka 없이 상태 기계 전체를 단위 시험',anchor='start',size=10.8,color='#475569',bg=False)

# lane3
Y3=770
node(40,Y3+8,120,60,'human','품질 담당자',['폼 로그인 · BCrypt'],rx=30)
node(190,Y3,190,76,'human','QMS 검토 화면',['jQuery + Tabler CSS','초안 수정 · 승인 · 반려'])
node(410,Y3,200,76,'app','NcrReviewService',['검토 이력 · CSRF · 역할 권한','승인 + 아웃박스 1 트랜잭션'])
node(650,Y3,150,76,'app','OutboxPublisher',['2초 주기 발행','실패 시스템만 재전송'])
topic(830,Y3+18,180,'quality.ncr.approved')
node(1040,Y3,165,76,'ext','가상 MES / ERP',['점검 지시 · 비용 전표','event_id 중복 제거'],dashed=True)
topic(1235,Y3+18,180,'integration.ncr.ack')
arrow(f'M160 {Y3+38} H188'); arrow(f'M380 {Y3+38} H408')
arrow(f'M510 {Y3} V735 H1478',color='#16a34a',w=2)
label(1000,739,'ncr → APPROVED · ncr_history · integration_outbox (한 트랜잭션)',size=11,color='#15803d',weight=700)
arrow(f'M1478 885 H725 V{Y3+78}',color='#16a34a')
label(1000,889,'미발행 아웃박스 조회',size=11,color='#15803d')
arrow(f'M800 {Y3+38} H828'); arrow(f'M1010 {Y3+38} H1038'); arrow(f'M1205 {Y3+38} H1233')
arrow(f'M1415 {Y3+38} H1478',color='#16a34a')

# 모바일 경보 알림
node(290,862,220,46,'app','AlertNotifier',['저장 뒤 웹훅 · 실패해도 분석 무관'])
node(40,862,190,46,'human','담당자 휴대폰',['ntfy·Slack · 누르면 QMS 화면'],rx=23)
arrow('M560 600 H395 V860',dashed=True,color='#db2777')
label(470,620,'새 초안 · 판단 보류 알림',size=10.8,color='#be185d')
arrow('M290 885 H232',color='#db2777')
arrow('M130 862 V848',dashed=True,color='#db2777')

# DB column
f,s,t=C['db']
o.append(f'<rect x="1480" y="92" width="200" height="900" rx="14" fill="{f}" stroke="{s}" stroke-width="2"/>')
o.append(f'<ellipse cx="1580" cy="96" rx="100" ry="10" fill="{f}" stroke="{s}" stroke-width="2"/>')
o.append(f'<text x="1580" y="132" text-anchor="middle" font-size="16" font-weight="800" fill="{t}">SQL Server 2025</text>')
o.append(f'<text x="1580" y="150" text-anchor="middle" font-size="11.5" fill="{t}">QUALIGUARD DB</text>')
groups=[(175,'공정 · 이상',['process_shot','anomaly_event','equipment']),
 (290,'기준 정보',['sensor_spec','maintenance_history']),
 (395,'에이전트',['agent_incident','agent_incident_event','agent_analysis']),
 (520,'부적합 · 지식저장소',['ncr','ncr_anomaly','ncr_history']),
 (690,'MES/ERP 연계',['integration_outbox','integration_status','integration_receipt','integration_ack_log']),
 (845,'보안',['app_user'])]
for gy,gt,items in groups:
    o.append(f'<text x="1496" y="{gy}" font-size="12" font-weight="800" fill="{t}">{E(gt)}</text>')
    for i,it in enumerate(items):
        o.append(f'<text x="1504" y="{gy+18+i*17}" font-size="11.5" font-family="{MONO}" fill="{t}">{E(it)}</text>')
o.append(f'<text x="1496" y="920" font-size="12" font-weight="800" fill="{t}">저장 프로시저 13개</text>')
o.append(f'<text x="1504" y="938" font-size="11" fill="{t}">usp_GetSensorTrend 외</text>')
o.append(f'<text x="1504" y="955" font-size="11" fill="{t}">(에이전트 도구 = SP)</text>')

# bottom row: MCP + legend
node(1050,945,180,46,'ext','외부 AI 클라이언트',[],dashed=True,rx=23)
node(1265,945,180,46,'app','MCP 서버  /mcp',['같은 조회 도구 6개'])
arrow('M1230 968 H1263'); arrow('M1445 968 H1478',color='#16a34a')
lx=30; ly=958
legend=[('edge','현장 (Python · MQTT)'),('app','Spring Boot 컴포넌트'),('rule','규칙 기반 에이전트'),('llm','LLM 에이전트'),('topic','Kafka 토픽'),('db','저장소'),('human','사람 · 화면'),('ext','외부 / 가상 시스템')]
for i,(k,txt) in enumerate(legend):
    f,s,t=C[k]; x=lx+(i%4)*240; y=ly+(i//4)*26
    o.append(f'<rect x="{x}" y="{y-11}" width="18" height="14" rx="3" fill="{f}" stroke="{s}" stroke-width="1.5"/>')
    o.append(f'<text x="{x+26}" y="{y}" font-size="12" fill="#334155">{E(txt)}</text>')

svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<defs>
<marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="context-stroke"/></marker>
<marker id="as" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="context-stroke"/></marker>
</defs>
<rect width="100%" height="100%" fill="#ffffff"/>
{chr(10).join(o)}
</svg>'''
open(sys.argv[1],'w').write(svg)
