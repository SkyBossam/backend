"""Local HTTP verification using an 8x8 black JPEG, never a real baby/camera image."""
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch
from aicoo_device import Agent
from camera_forwarder import CameraForwarder
from smoke_local import BASE, request
from start_aicoo import main as launch

JPEG=base64.b64decode('/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q==')


def main():
    account=request('/auth/signup',{'email':'camera-test-%s@example.test'%uuid.uuid4(),'password':uuid.uuid4().hex})
    request('/auth/verify-email',{'token':account['verificationToken']})
    token,workspace=account['accessToken'],account['workspaceId']
    child=request('/children',{'displayName':'영상 통신 검증용 아이','birthDate':'2026-01-01'},token,workspace)
    with tempfile.TemporaryDirectory(prefix='aicoo-camera-test-') as folder:
        root=Path(folder);state=root/'identity';model=root/'model';model.mkdir()
        agent=Agent(BASE,state,allow_http=True);agent.load_key(create=True)
        pending=agent.request('/pairing/start',agent.proof('pair'))
        paired=request('/device-pairings/approve',{'code':pending['userCode'],'childId':child['id'],'name':'영상 검증용 파이'},token,workspace)
        device=paired['deviceId']
        try:
            agent.save_credential(agent.request('/pairing/token',{'deviceCode':pending['deviceCode']}))
            args=['start_aicoo.py','--state-dir',str(state),'--sky-eye-dir',str(model),'--config',str(root/'launcher.json'),'--no-start-model','--once']
            with patch('sys.argv',args): assert launch()==0
            # The launcher rotated the token, so read the new credential from private state.
            agent=Agent(BASE,state,allow_http=True)
            class Preview(BaseHTTPRequestHandler):
                def log_message(self,*unused): pass
                def do_GET(self):
                    assert not self.headers.get('X-Device-Token') and not self.headers.get('Authorization')
                    value=JPEG if self.path=='/frame.jpg' else json.dumps({'wall_time_unix':time.time(),'preview_age_s':.1}).encode()
                    self.send_response(200);self.send_header('Content-Length',str(len(value)));self.end_headers();self.wfile.write(value)
            server=ThreadingHTTPServer(('127.0.0.1',0),Preview);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try: CameraForwarder(agent,server.server_address[1]).send_once()
            finally: server.shutdown();server.server_close();thread.join(timeout=3)
            path=BASE+'/api/v1/children/'+child['id']+'/devices/'+device+'/camera/frame'
            headers={'Authorization':'Bearer '+token,'X-Workspace-Id':workspace}
            with urlopen(Request(path,headers=headers),timeout=5) as response:
                assert response.status==200 and response.read()==JPEG
            try: urlopen(path,timeout=5);raise AssertionError('Anonymous image access was accepted')
            except HTTPError as error: assert error.code==401
            time.sleep(3.1)
            with urlopen(Request(path,headers=headers),timeout=5) as response: assert response.status==204
        finally:
            request('/devices/'+device,token=token,workspace=workspace,method='DELETE')
            request('/auth/logout',{},token)
    print('PASS: saved connection -> one-command launcher -> data, loopback preview -> authenticated relay -> exact JPEG, anonymous rejection and stale image expiry.')


if __name__=='__main__': main()
