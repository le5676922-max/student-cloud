"""Bounded loopback-only SMTP inbox for the disposable business workflow fixture.

Only explicitly allowed .invalid recipients are accepted. Mail and verification
codes stay in memory and are never logged. Run this file for its stdlib tests.
"""
from collections import deque
from email import policy
from email.parser import BytesParser
from email.utils import getaddresses
import re
import socket
import socketserver
import threading
import time


_MAILBOX = re.compile(r'[A-Za-z0-9.!#$%&\'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.invalid\Z')
_CODE = re.compile(r'(?<![0-9])[0-9]{6}(?![0-9])')


def _mailbox(value):
    if not isinstance(value, str) or len(value) > 254 or not _MAILBOX.fullmatch(value):
        raise ValueError('Fixture requires an explicit .invalid mailbox')
    return value.lower()


def _envelope_mailbox(value):
    try:
        return _mailbox(value)
    except ValueError:
        return None


def _verification_code(raw, recipient, sender):
    message = BytesParser(policy=policy.default).parsebytes(raw)
    if message.defects:
        raise ValueError('Malformed fixture message')
    for header, expected in (('To', recipient), ('From', sender)):
        addresses = getaddresses(message.get_all(header, []))
        if len(addresses) != 1 or _mailbox(addresses[0][1]) != expected:
            raise ValueError('Fixture message does not match its envelope')
    bodies = []
    for part in message.walk():
        if part.defects:
            raise ValueError('Malformed fixture MIME part')
        if part.get_content_type() == 'text/plain' and not part.is_attachment():
            text = part.get_content()
            if isinstance(text, str):
                bodies.append(text)
    matches = set(_CODE.findall('\n'.join(bodies)))
    if len(matches) != 1:
        raise ValueError('Fixture expected exactly one verification code')
    return matches.pop()


class LocalSmtpFixture:
    """Small SMTP sink, with finite commands, bytes, recipients and queued codes."""

    def __init__(self, recipients, *, sender='fixture@example.invalid',
                 max_message_bytes=65536, max_messages=32):
        self.recipients = frozenset(_mailbox(value) for value in recipients)
        self.sender = _mailbox(sender)
        if not 1 <= len(self.recipients) <= 20:
            raise ValueError('Fixture requires 1 to 20 allowed recipients')
        if not 256 <= max_message_bytes <= 1048576 or not 1 <= max_messages <= 100:
            raise ValueError('Invalid fixture memory bounds')
        self.max_message_bytes = max_message_bytes
        self.max_messages = max_messages
        self._codes = {recipient: deque() for recipient in self.recipients}
        self._condition = threading.Condition()
        self._connections = set()
        self._closed = False
        fixture = self

        class Handler(socketserver.StreamRequestHandler):
            def setup(self):
                self.request.settimeout(5)
                super().setup()

            def reply(self, value):
                self.wfile.write(value + b'\r\n')
                self.wfile.flush()

            def handle(self):
                try:
                    self.session()
                except (OSError, ValueError, UnicodeError):
                    # Never expose SMTP content or recipient/code-bearing errors.
                    return

            def session(self):
                self.reply(b'220 localhost fixture SMTP')
                greeted = False
                mail_from = None
                recipient = None
                for _ in range(100):
                    line = self.rfile.readline(513)
                    if not line:
                        return
                    if len(line) > 512 or not line.endswith(b'\r\n'):
                        self.reply(b'500 Invalid command size')
                        return
                    command = line[:-2].decode('ascii')
                    verb, _, argument = command.partition(' ')
                    verb = verb.upper()
                    if verb in ('EHLO', 'HELO') and argument:
                        greeted = True
                        mail_from = recipient = None
                        if verb == 'EHLO':
                            self.reply(b'250-localhost')
                            self.reply(('250 SIZE ' + str(fixture.max_message_bytes)).encode('ascii'))
                        else:
                            self.reply(b'250 localhost')
                    elif verb == 'QUIT':
                        self.reply(b'221 Bye')
                        return
                    elif verb == 'RSET':
                        mail_from = recipient = None
                        self.reply(b'250 Reset')
                    elif verb == 'NOOP':
                        self.reply(b'250 OK')
                    elif verb == 'MAIL' and greeted:
                        mail_from = recipient = None
                        match = re.fullmatch(r'FROM:<([^<>]+)>(?: SIZE=([0-9]+))?', argument, re.I)
                        if not match or _envelope_mailbox(match[1]) != fixture.sender:
                            self.reply(b'550 Sender not allowed')
                        elif match[2] and int(match[2]) > fixture.max_message_bytes:
                            self.reply(b'552 Message too large')
                        else:
                            mail_from = fixture.sender
                            self.reply(b'250 Sender accepted')
                    elif verb == 'RCPT' and mail_from:
                        match = re.fullmatch(r'TO:<([^<>]+)>', argument, re.I)
                        target = _envelope_mailbox(match[1]) if match else None
                        if target not in fixture.recipients:
                            self.reply(b'550 Recipient not allowed')
                        elif recipient is not None:
                            self.reply(b'452 One recipient per message')
                        else:
                            recipient = target
                            self.reply(b'250 Recipient accepted')
                    elif verb == 'DATA' and not argument and recipient:
                        self.reply(b'354 End with a dot')
                        raw = bytearray()
                        while True:
                            chunk = self.rfile.readline(fixture.max_message_bytes + 1)
                            if not chunk:
                                return
                            if chunk == b'.\r\n':
                                break
                            if chunk.startswith(b'..'):
                                chunk = chunk[1:]
                            if len(raw) + len(chunk) > fixture.max_message_bytes:
                                self.reply(b'552 Message too large')
                                return
                            raw.extend(chunk)
                        try:
                            fixture._accept(bytes(raw), recipient)
                        except ValueError:
                            self.reply(b'554 Invalid fixture message')
                        else:
                            self.reply(b'250 Message accepted')
                        mail_from = recipient = None
                    else:
                        self.reply(b'503 Unsupported command or sequence')
                self.reply(b'421 Command limit reached')

        class Server(socketserver.TCPServer):
            request_queue_size = 4

            def get_request(self):
                connection, address = super().get_request()
                with fixture._condition:
                    fixture._connections.add(connection)
                return connection, address

            def close_request(self, request):
                super().close_request(request)
                with fixture._condition:
                    fixture._connections.discard(request)

            def handle_error(self, request, client_address):
                # socketserver's default prints tracebacks; no message data is logged.
                pass

        self._server = Server(('127.0.0.1', 0), Handler)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(
            target=lambda: self._server.serve_forever(poll_interval=0.05), daemon=True)
        self._thread.start()

    def environment(self):
        return {
            'SC_MAIL_ENABLED': 'true', 'SC_SMTP_HOST': '127.0.0.1',
            'SC_SMTP_PORT': str(self.port), 'SC_SMTP_AUTH': 'false',
            'SC_SMTP_SSL': 'false', 'SC_SMTP_STARTTLS': 'false',
            'SC_SMTP_USERNAME': self.sender, 'SC_SMTP_PASSWORD': '',
        }

    def _accept(self, raw, recipient):
        if len(raw) > self.max_message_bytes or recipient not in self.recipients:
            raise ValueError('Fixture message rejected')
        code = _verification_code(raw, recipient, self.sender)
        with self._condition:
            if self._closed or sum(map(len, self._codes.values())) >= self.max_messages:
                raise ValueError('Fixture inbox full or closed')
            self._codes[recipient].append(code)
            self._condition.notify_all()

    def wait_for_code(self, recipient, timeout=10):
        recipient = _mailbox(recipient)
        if recipient not in self.recipients or not 0 < timeout <= 30:
            raise ValueError('Invalid fixture mailbox or timeout')
        deadline = time.monotonic() + timeout
        with self._condition:
            while not self._codes[recipient]:
                remaining = deadline - time.monotonic()
                if self._closed or remaining <= 0:
                    raise RuntimeError('Fixture verification mail was not received')
                self._condition.wait(remaining)
            return self._codes[recipient].popleft()

    def close(self):
        with self._condition:
            if self._closed:
                return
            self._closed = True
            for codes in self._codes.values():
                codes.clear()
            self._condition.notify_all()
            connections = tuple(self._connections)
        for connection in connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=6)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


if __name__ == '__main__':
    import smtplib
    import unittest
    from email.message import EmailMessage

    class FixtureTests(unittest.TestCase):
        recipient = 'qa-member@example.invalid'

        def message(self, *, cte='8bit'):
            message = EmailMessage()
            message['From'] = 'fixture@example.invalid'
            message['To'] = self.recipient
            message['Subject'] = '学生云盘注册验证码'
            message.set_content('验证码：123456\n十分钟内有效。', charset='utf-8', cte=cte)
            return message

        def test_utf8_and_base64_messages(self):
            with LocalSmtpFixture([self.recipient]) as fixture:
                self.assertEqual(fixture._server.server_address[0], '127.0.0.1')
                for cte in ('8bit', 'base64'):
                    with smtplib.SMTP('127.0.0.1', fixture.port, timeout=3) as smtp:
                        smtp.send_message(self.message(cte=cte))
                    # Avoid assertion output containing the code on a test failure.
                    self.assertTrue(fixture.wait_for_code(self.recipient) == '123456')

        def test_declared_and_actual_oversize_are_rejected(self):
            with LocalSmtpFixture([self.recipient], max_message_bytes=512) as fixture:
                with smtplib.SMTP('127.0.0.1', fixture.port, timeout=3) as smtp:
                    smtp.ehlo()
                    self.assertEqual(smtp.mail(fixture.sender, options=['SIZE=513'])[0], 552)
                    self.assertEqual(smtp.mail(fixture.sender)[0], 250)
                    self.assertEqual(smtp.rcpt(self.recipient)[0], 250)
                    self.assertEqual(smtp.data(b'x' * 513)[0], 552)
                self.assertFalse(any(fixture._codes.values()))

        def test_recipient_allowlist_envelope_and_queue_are_bounded(self):
            with LocalSmtpFixture([self.recipient], max_messages=1) as fixture:
                with smtplib.SMTP('127.0.0.1', fixture.port, timeout=3) as smtp:
                    smtp.ehlo()
                    smtp.mail(fixture.sender)
                    self.assertEqual(smtp.rcpt('other@example.invalid')[0], 550)
                    self.assertEqual(smtp.rcpt('external@example.com')[0], 550)
                    self.assertEqual(smtp.rcpt(self.recipient)[0], 250)
                    self.assertEqual(smtp.rcpt(self.recipient)[0], 452)
                    smtp.data(self.message().as_bytes(policy=policy.SMTP))
                    with self.assertRaises(smtplib.SMTPDataError):
                        smtp.send_message(self.message())
                self.assertTrue(fixture.wait_for_code(self.recipient) == '123456')
                wrong_envelope = self.message().as_bytes(policy=policy.SMTP).replace(
                    self.recipient.encode(), b'other@example.invalid')
                with self.assertRaises(ValueError):
                    fixture._accept(wrong_envelope, self.recipient)

        def test_closed_fixture_discards_codes_and_closes_listener(self):
            fixture = LocalSmtpFixture([self.recipient])
            fixture._accept(self.message().as_bytes(policy=policy.SMTP), self.recipient)
            fixture.close()
            fixture.close()
            self.assertFalse(any(fixture._codes.values()))
            with self.assertRaises(OSError):
                socket.create_connection(('127.0.0.1', fixture.port), timeout=1)

        def test_close_bounds_an_unfinished_smtp_session(self):
            fixture = LocalSmtpFixture([self.recipient])
            with socket.create_connection(('127.0.0.1', fixture.port), timeout=1) as client:
                self.assertTrue(client.recv(512).startswith(b'220 '))
                start = time.monotonic()
                fixture.close()
                # Windows may finish its current socket wait before noticing shutdown.
                self.assertLess(time.monotonic() - start, 6)
                self.assertEqual(client.recv(512), b'')

    unittest.main()
