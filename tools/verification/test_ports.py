import os
from pathlib import Path
import re
import socket
import unittest
from unittest import mock

from support import ports

TOOLS = Path(__file__).resolve().parent
APP_PORTS = range(8080, 8093)


class PortOffsetsTest(unittest.TestCase):
    """결정 105: 도구마다 다른 고정 간격, 임시 포트 범위 아래, 시작 전 사용 중 확인."""

    def tools_with_application_ports(self):
        return sorted(path.stem for path in TOOLS.glob('verify-*.py') if 'offset' in path.read_text())

    def test_every_tool_with_application_ports_has_its_own_offset(self):
        self.assertEqual(self.tools_with_application_ports(), sorted(ports.OFFSETS))

    def test_tool_port_ranges_do_not_overlap_and_stay_below_the_ephemeral_range(self):
        taken = {}
        for tool, offset in ports.OFFSETS.items():
            for port in APP_PORTS:
                self.assertLess(offset + port, ports.EPHEMERAL_START, tool)
                self.assertNotIn(offset + port, taken, tool + ' overlaps ' + taken.get(offset + port, ''))
                taken[offset + port] = tool

    def test_tools_take_offsets_only_from_the_shared_module(self):
        raw = [path.name for path in TOOLS.glob('verify-*.py')
               if re.search(r'offset\s*=\s*int\(os\.environ', path.read_text()) or 'def free_offset' in path.read_text()]
        self.assertEqual([], raw)

    def test_tools_do_not_repeat_their_own_application_port_bind_check(self):
        # 도구마다 따로 bind하던 확인은 SO_REUSEADDR 유무가 달라, 앞 실행의 TIME_WAIT만 남아도 멈췄다.
        own = [path.name for path in TOOLS.glob('verify-*.py') if 'bind(("127.0.0.1",port+offset))' in path.read_text()]
        self.assertEqual([], own)

    def test_busy_port_fails_before_starting_anything(self):
        with socket.socket() as busy:
            busy.bind(('127.0.0.1', ports.OFFSETS['verify-license'] + 8084))
            busy.listen()
            with self.assertRaisesRegex(RuntimeError, 'in use'):
                ports.application_offset('verify-license', 'LICENSE_TEST_PORT_OFFSET', [8080, 8084])

    def test_port_left_in_time_wait_by_a_previous_run_is_not_reported_busy(self):
        # 앞 실행의 앱이 먼저 연결을 끊으면 그 포트가 TIME_WAIT로 남는다. 새로 listen할 수 있으므로 사용 중이 아니다.
        port = ports.OFFSETS['verify-review'] + 8086
        with socket.socket() as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(('127.0.0.1', port))
            server.listen()
            client = socket.create_connection(('127.0.0.1', port))
            accepted, _ = server.accept()
            accepted.close()
            client.recv(1)
            client.close()
        self.assertEqual(ports.OFFSETS['verify-review'],
                         ports.application_offset('verify-review', 'REVIEW_TEST_PORT_OFFSET', [8086]))

    def test_override_is_checked_against_the_ephemeral_range(self):
        with mock.patch.dict(os.environ, {'LICENSE_TEST_PORT_OFFSET': '30000'}):
            with self.assertRaisesRegex(RuntimeError, 'ephemeral'):
                ports.application_offset('verify-license', 'LICENSE_TEST_PORT_OFFSET', [8080, 8084])

    def test_override_cannot_push_an_application_port_to_zero_or_below(self):
        # PR #118 리뷰: 음수 간격이면 --server.port=0이 되어 앱이 임의 포트로 뜨고 준비 확인이 끝까지 실패한다.
        with mock.patch.dict(os.environ, {'STUDIO_TEST_PORT_OFFSET': '-8080'}):
            with self.assertRaisesRegex(RuntimeError, 'below 1'):
                ports.application_offset('verify-studio', 'STUDIO_TEST_PORT_OFFSET', [8080, 8085])

    def test_free_default_offset_is_returned(self):
        self.assertEqual(ports.OFFSETS['verify-studio'],
                         ports.application_offset('verify-studio', 'STUDIO_TEST_PORT_OFFSET', [8080, 8085]))


class ApplicationJarUseTest(unittest.TestCase):
    """도구는 실행 JAR을 support.boot_jars.application_jar로만 고른다(-test-fixtures.jar 오선택 방지)."""

    def test_no_tool_globs_jars_directly(self):
        raw = [path.name for path in TOOLS.glob('*.py') if re.search(r'glob\(["\']\*\.jar', path.read_text())]
        self.assertEqual([], raw)


if __name__ == '__main__':
    unittest.main()
