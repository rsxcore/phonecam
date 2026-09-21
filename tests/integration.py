"""Integration test: real TCP MJPEG -> receiver -> actual DirectShow capture.
Uses only generated test images; never accesses a physical camera.
"""
import socket, threading, time, subprocess, pathlib, sys, json, io, ctypes, mmap, struct
from PIL import Image, ImageDraw
ROOT=pathlib.Path(__file__).resolve().parents[1]
WORK=ROOT/'work'/'integration';WORK.mkdir(parents=True,exist_ok=True)
EXE=ROOT/'server'/'target'/'release'/'phonecam-server.exe'
class Phone:
    def __init__(self):
        self.rotation=0;self.mode='normal';self.count=0;self.running=True
        self.sock=socket.socket();self.sock.bind(('127.0.0.1',0));self.port=self.sock.getsockname()[1];self.sock.listen()
        image=Image.new('RGB',(640,360));d=ImageDraw.Draw(image)
        d.rectangle((0,0,319,179),fill=(240,20,20));d.rectangle((320,0,639,179),fill=(20,230,30));d.rectangle((0,180,319,359),fill=(20,30,240));d.rectangle((320,180,639,359),fill=(235,220,20))
        out=io.BytesIO();image.save(out,format='JPEG',quality=95);self.jpeg=out.getvalue()
        threading.Thread(target=self.accept,daemon=True).start()
    def accept(self):
        while self.running:
            try:c,_=self.sock.accept()
            except OSError:return
            threading.Thread(target=self.serve,args=(c,),daemon=True).start()
    def serve(self,c):
        with c:
            try:
                c.settimeout(3);req=b''
                while b'\r\n\r\n' not in req:req+=c.recv(1024)
                c.sendall(b'HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=test\r\n\r\n')
                while self.running:
                    if self.mode=='drop':return
                    if self.mode=='stall':time.sleep(.05);continue
                    if self.mode=='bad':c.sendall(b'--test\r\nContent-Length: 99999999\r\n\r\n');return
                    frame=b'--test\r\nContent-Type: image/jpeg\r\nContent-Length: '+str(len(self.jpeg)).encode()+b'\r\nX-PhoneCam-Rotation: '+str(self.rotation).encode()+b'\r\n\r\n'+self.jpeg+b'\r\n'
                    c.sendall(frame);self.count+=1;time.sleep(1/30)
            except OSError:return
    def close(self):self.running=False;self.sock.close()
def capture(name,seconds=1,size='1280x720'):
    file=WORK/(name+'.rgb')
    cmd=['ffmpeg','-y','-hide_banner','-loglevel','error','-f','dshow','-video_size',size,'-i','video=PhoneCam','-t',str(seconds),'-pix_fmt','rgb24','-f','rawvideo',str(file)]
    r=subprocess.run(cmd,capture_output=True,timeout=20)
    assert r.returncode==0,r.stderr.decode(errors='replace')
    w,h=map(int,size.split('x'));data=file.read_bytes();n=len(data)//(w*h*3);assert n>=seconds*25,(name,n)
    last=Image.frombytes('RGB',(w,h),data[-w*h*3:]);last.save(WORK/(name+'.png'));file.unlink()
    return last,n
def similar(actual,expected,tolerance=22):assert max(abs(a-b) for a,b in zip(actual,expected))<=tolerance,(actual,expected)
def read_mapping():
    return mmap.mmap(-1,16588864,tagname='Local\\PhoneCam_Frame_v1',access=mmap.ACCESS_READ)
def start(phone):return subprocess.Popen([str(EXE),'--url',f'http://127.0.0.1:{phone.port}/stream','--status',str(WORK/'status.txt')],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
def main():
    phone=Phone();proc=start(phone);report={}
    try:
        time.sleep(2)
        img,n=capture('landscape',2);similar(img.getpixel((100,100)),(240,20,20));similar(img.getpixel((1100,600)),(235,220,20));report['landscape_frames_2s']=n
        for rotation,corners in [(90,[(20,30,240),(240,20,20),(235,220,20),(20,230,30)]),(180,[(235,220,20),(20,30,240),(20,230,30),(240,20,20)]),(270,[(20,230,30),(235,220,20),(240,20,20),(20,30,240)])]:
            phone.rotation=rotation;time.sleep(.4);img,n=capture('rotation'+str(rotation));points=[(490,100),(790,100),(490,600),(790,600)] if rotation%180 else [(100,100),(1100,100),(100,600),(1100,600)]
            for pos,color in zip(points,corners):similar(img.getpixel(pos),color)
            if rotation%180:similar(img.getpixel((100,300)),(0,0,0))
            report['rotation_'+str(rotation)]='pass'
        phone.rotation=0
        img,n=capture('scaled640',1,'640x480');similar(img.getpixel((50,100)),(240,20,20));similar(img.getpixel((50,10)),(0,0,0));report['scaled_640x480']='pass'
        held=read_mapping()
        duplicate=subprocess.run([str(EXE),'--test'],capture_output=True,timeout=5);assert duplicate.returncode!=0;report['duplicate_writer_rejected']='pass'
        proc.terminate();proc.wait(5);time.sleep(3)
        img,n=capture('stopped');similar(img.getpixel((100,100)),(30,20,24));report['stale_frame_cleared']='pass'
        proc=start(phone);time.sleep(2);img,n=capture('restart');similar(img.getpixel((100,100)),(240,20,20));held.close();report['restart_with_reader_holding_mapping']='pass'
        for mode in ['stall','drop','bad']:
            phone.mode=mode;time.sleep(5);img,n=capture(mode);similar(img.getpixel((100,100)),(30,20,24));phone.mode='normal';time.sleep(3);img,n=capture(mode+'_recovered');similar(img.getpixel((100,100)),(240,20,20));report[mode+'_reconnect']='pass'
        report['status']=(WORK/'status.txt').read_text();report['network_frames']=phone.count
        print(json.dumps(report,indent=2));(WORK/'results.json').write_text(json.dumps(report,indent=2))
    finally:
        proc.terminate();proc.wait(5);phone.close()
if __name__=='__main__':main()
