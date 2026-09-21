"""90s real DirectShow graph survives receiver restart and network loss."""
import sys,pathlib,threading,time,subprocess,io,json
sys.path.insert(0,str(pathlib.Path(__file__).parent))
from integration import Phone,WORK,EXE,ROOT
from PIL import Image
class FastPhone(Phone):
    def serve(self,c):
        with c:
            try:
                c.settimeout(3);req=b''
                while b'\r\n\r\n' not in req:req+=c.recv(1024)
                c.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=test\r\n\r\n')
                next_frame=time.perf_counter()
                while self.running:
                    if self.mode=='drop':return
                    if self.mode=='stall':time.sleep(.02);continue
                    b=frames[self.count%len(frames)]
                    c.sendall(b'--test\r\nContent-Type: image/jpeg\r\nContent-Length: '+str(len(b)).encode()+b'\r\n\r\n'+b+b'\r\n');self.count+=1
                    next_frame+=1/30;time.sleep(max(0,next_frame-time.perf_counter()))
            except OSError:return
frames=[]
for i in range(16):
    out=io.BytesIO();Image.new('RGB',(1280,720),(40+i*10,60+i*7,220-i*10)).save(out,format='JPEG',quality=85);frames.append(out.getvalue())
phone=FastPhone()
def receiver():return subprocess.Popen([str(EXE),'--url',f'127.0.0.1:{phone.port}','--status',str(WORK/'soak-status.txt')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
proc=receiver();time.sleep(2)
log=open(WORK/'soak-ffmpeg.log','wb')
capture=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','error','-f','dshow','-video_size','1280x720','-i','video=PhoneCam','-t','90','-vf','scale=64:36','-pix_fmt','rgb24','-f','rawvideo','pipe:1'],stdout=subprocess.PIPE,stderr=log)
observed=[]
def read():
    while True:
        b=capture.stdout.read(64*36*3)
        if not b:break
        assert len(b)==64*36*3
        colors=list(zip(b[0::3],b[1::3],b[2::3]));spread=max(max(c[k] for c in colors)-min(c[k] for c in colors) for k in range(3))
        observed.append((time.monotonic(),colors[0],spread))
thread=threading.Thread(target=read);thread.start();begin=time.monotonic()
try:
    time.sleep(15);proc.terminate();proc.wait(5)
    time.sleep(5);proc=receiver()
    time.sleep(20);phone.mode='drop'
    time.sleep(6);phone.mode='normal'
    capture.wait(timeout=65);thread.join(3)
    assert capture.returncode==0
    bad=[r for r in observed if r[2]>3];assert not bad,len(bad)
    def live(a,b):return sum(1 for t,c,_ in observed if a<t-begin<b and c!=(30,20,24))
    assert live(0,12)>300 and live(24,38)>350 and live(52,88)>950
    assert any(c==(30,20,24) for t,c,_ in observed if 18<t-begin<20)
    assert len(observed)>=2650,len(observed)
    report={'duration_seconds':round(time.monotonic()-begin,2),'directshow_frames':len(observed),'torn_frames':len(bad),'same_graph_receiver_restart':'pass','same_graph_network_reconnect':'pass','last_status':(WORK/'soak-status.txt').read_text()}
    (WORK/'soak-results.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
finally:
    if capture.poll() is None:capture.terminate()
    proc.terminate();proc.wait(5);phone.close();log.close()
