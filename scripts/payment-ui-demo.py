"""Synthetic, localhost-only UI fixture. No database, login, provider or real money.

Run: python scripts/payment-ui-demo.py
Open http://127.0.0.1:18765/payments . Financial behavior is tested in Java, not by this fixture.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
import json
import mimetypes

STATIC = Path(__file__).resolve().parents[1] / 'src/main/resources/static'
TIME = '2030-01-02T01:00:00Z'
PERMISSIONS = ['PAYMENT_VIEW', 'PAYMENT_RECONCILE', 'PAYMENT_AUDIT', 'PAYMENT_MANAGE', 'REFUND_REQUEST', 'REFUND_APPROVE', 'REFUND_EXECUTE']
ORDERS = [dict(order_code=100001+i,amount_vnd=49000,duration_days=30,status=status,currency='VND',product_code='PREMIUM_30D',
               policy_version='DEMO_ONLY',created_at=TIME,paid_at=TIME if status=='PAID' else None,expires_at=TIME,edit_version=1)
          for i,status in enumerate(['PAID','PENDING','EXPIRED','CANCELLED'])]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, data, status=200):
        body=json.dumps(data,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)

    def do_GET(self):
        url=urlsplit(self.path);path=url.path;query=parse_qs(url.query)
        if path=='/api/auth/csrf':return self.send(dict(headerName='X-DEMO-CSRF',token='synthetic'))
        if path.startswith('/api/admin/payments'):
            route=path.removeprefix('/api/admin/payments')
            if route=='/me':return self.send(dict(permissions=PERMISSIONS,actorId=9001,totpEnabled=False,refundsEnabled=False,partialRefundsEnabled=False,automaticPayouts=False))
            if route=='/orders':
                items=[o for o in ORDERS if (not query.get('q') or str(o['order_code'])==query['q'][0]) and (not query.get('status') or o['status']==query['status'][0])]
                return self.send(dict(items=items,total=len(items),page=0,size=25))
            if route.startswith('/orders/') and route.split('/')[-1].isdigit():
                order=next((o for o in ORDERS if str(o['order_code'])==route.split('/')[-1]),None)
                if order:
                    paid=order['status']=='PAID'
                    return self.send(order|dict(receipts=[dict(id='demo-receipt',reference='••••7341',amount_vnd=49000,bank_verified=True,effective_at=TIME)] if paid else [],
                        events=[dict(id='demo-event',kind='RECEIPT',source='WEBHOOK',recorded_at=TIME)] if paid else [],
                        grants=[dict(id='demo-grant',source='WEBHOOK',starts_at=TIME,ends_at='2030-02-01T01:00:00Z',revoked_seconds=0)] if paid else [],
                        journals=[dict(kind='RECEIPT',account_code='COLLECTION_CLEARING',debit_vnd=49000,credit_vnd=0,recorded_at=TIME),dict(kind='RECEIPT',account_code='UNALLOCATED',debit_vnd=0,credit_vnd=49000,recorded_at=TIME)] if paid else [],cases=[],refunds=[],runs=[]))
            if route=='/reconciliation-cases':return self.send(dict(items=[dict(id='demo-case',order_code=100003,kind='MISSING_RECEIPT_REFERENCES',status='OPEN',evidence='Dữ liệu giả: provider báo PAID nhưng thiếu mã tham chiếu.',due_at=TIME,version=0)],total=1,page=0,size=25))
            if route=='/refunds':return self.send(dict(items=[dict(id='demo-refund',order_code=100001,amount_vnd=49000,status='UNKNOWN',version=2,recipient_mask='MB · ••••6789',reason='Mô phỏng kết quả chưa rõ',created_at=TIME)],total=1,page=0,size=25))
            if route=='/refunds/demo-refund':return self.send(dict(id='demo-refund',order_code=100001,amount_vnd=49000,status='UNKNOWN',seconds_to_revoke=2592000,recipient_mask='MB · ••••6789',version=2))
            if route=='/reports':return self.send(dict(cutoff=TIME,receiptsVnd=49000,refundsVnd=0,netReceiptsVnd=49000,journalImbalances=0,unresolvedCases=1,acceptedExceptions=0,balances=[dict(account_code='COLLECTION_CLEARING',debit_vnd=49000,credit_vnd=0),dict(account_code='DEFERRED_SERVICE',debit_vnd=0,credit_vnd=49000)]))
            if route in ['/statements','/closes','/close-reopens']:return self.send([])
            if route=='/audit':return self.send([dict(created_at=TIME,action='PAYMENT_APPLIED',resource_id='100001',reason='SYNTHETIC DEMO · no real money')])
            return self.send(dict(message='Dữ liệu demo chỉ có các tình huống được mô tả.'),404)
        relative='payments.html' if path=='/payments' else path.lstrip('/')
        candidate=(STATIC/relative).resolve()
        if not candidate.is_relative_to(STATIC.resolve()) or not candidate.is_file() or not (relative=='payments.html' or relative.startswith(('css/','js/','fonts/')) or relative=='favicon.svg'):
            return self.send(dict(message='Not found'),404)
        body=candidate.read_bytes()
        if relative=='payments.html':body=body.replace(b'<h1 id="payment-title">','<p role="note">DEMO GIẢ · Không kết nối ngân hàng</p><h1 id="payment-title">'.encode())
        self.send_response(200);self.send_header('Content-Type',mimetypes.guess_type(str(candidate))[0] or 'application/octet-stream')
        self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)

    def do_POST(self):
        self.send(dict(message='Demo giao diện chỉ đọc. Chạy bộ test Java để kiểm chứng thao tác và lỗi.'),409)


if __name__=='__main__':
    print('Synthetic UI demo: http://127.0.0.1:18765/payments',flush=True)
    ThreadingHTTPServer(('127.0.0.1',18765),Handler).serve_forever()
