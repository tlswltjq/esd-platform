import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from support import resource_sampling  # noqa: E402


class ServicesTest(unittest.TestCase):
    def test_every_run_samples_the_business_db_and_kafka_and_adds_the_chosen_broker(self):
        self.assertEqual(resource_sampling.sampled_services("kafka"), ["mysql", "kafka"])
        self.assertEqual(resource_sampling.sampled_services("direct"), ["mysql", "kafka"])
        self.assertEqual(resource_sampling.sampled_services("rabbitmq"), ["mysql", "kafka", "rabbitmq"])
        self.assertEqual(resource_sampling.sampled_services("redis-streams"), ["mysql", "kafka", "streams"])

    def test_the_broker_of_a_run_is_the_container_carrying_its_messages(self):
        self.assertEqual(resource_sampling.broker_service("kafka"), "kafka")
        self.assertEqual(resource_sampling.broker_service("rabbitmq"), "rabbitmq")
        self.assertEqual(resource_sampling.broker_service("redis-streams"), "streams")
        self.assertIsNone(resource_sampling.broker_service("direct"))

    def test_unknown_broker_is_refused(self):
        with self.assertRaises(ValueError):
            resource_sampling.sampled_services("nats")


class ParseTest(unittest.TestCase):
    def test_docker_memory_units_become_bytes(self):
        self.assertEqual(resource_sampling.memory_bytes("512B"), 512)
        self.assertEqual(resource_sampling.memory_bytes("1.5KiB"), 1536)
        self.assertEqual(resource_sampling.memory_bytes("123.4MiB"), round(123.4 * 1024 ** 2))
        self.assertEqual(resource_sampling.memory_bytes("1.2GiB"), round(1.2 * 1024 ** 3))
        self.assertEqual(resource_sampling.memory_bytes("10MB"), 10 * 1000 ** 2)
        self.assertEqual(resource_sampling.memory_bytes("2kB"), 2000)

    def test_unknown_unit_is_refused(self):
        with self.assertRaises(ValueError):
            resource_sampling.memory_bytes("3TiX")

    def test_stats_line_gives_cpu_percent_and_used_memory(self):
        sample = resource_sampling.parse_stats_line("abc123\t12.34%\t200MiB / 768MiB")
        self.assertEqual(sample, ("abc123", 12.34, 200 * 1024 ** 2))

    def test_du_output_is_summed_over_the_listed_paths(self):
        self.assertEqual(resource_sampling.parse_du("120\t/tmp/kraft-combined-logs\n8\t/tmp/kafka-logs\n"), 128)
        self.assertEqual(resource_sampling.parse_du(""), 0)


class SummaryTest(unittest.TestCase):
    def test_summary_per_service_has_mean_and_max(self):
        samples = [("mysql", 10.0, 100), ("mysql", 30.0, 300), ("kafka", 5.0, 1000)]
        summary = resource_sampling.summarize(samples)
        self.assertEqual(summary["mysql"], {"samples": 2, "cpu_percent_mean": 20.0, "cpu_percent_max": 30.0,
                                            "memory_mib_mean": round(200 / 1024 ** 2, 1),
                                            "memory_mib_max": round(300 / 1024 ** 2, 1)})
        self.assertEqual(summary["kafka"]["samples"], 1)

    def test_no_samples_is_an_empty_summary_not_zero(self):
        self.assertEqual(resource_sampling.summarize([]), {})


class SamplerTest(unittest.TestCase):
    def test_sampler_reads_stats_until_stopped_and_names_rows_by_service(self):
        calls = []

        def capture(command, **kwargs):
            calls.append(command)
            return "id-m\t1.00%\t1MiB / 2MiB\nid-k\t2.00%\t2MiB / 4MiB"

        sampler = resource_sampling.Sampler(capture, {"mysql": "id-m", "kafka": "id-k"}, interval=0.01)
        sampler.start()
        while len(calls) < 3:
            pass
        samples = sampler.stop()
        self.assertGreaterEqual(len(samples), 6)
        self.assertEqual({service for service, _, _ in samples}, {"mysql", "kafka"})
        self.assertEqual(calls[0][:3], ["docker", "stats", "--no-stream"])
        self.assertIn("id-m", calls[0])

    def test_sampler_stopped_at_once_still_has_one_reading(self):
        def capture(command, **kwargs):
            return "id-m\t1.00%\t1MiB / 2MiB"

        sampler = resource_sampling.Sampler(capture, {"mysql": "id-m"}, interval=60)
        sampler.start()
        self.assertEqual(len(sampler.stop()), 1)

    def test_failed_reading_is_counted_and_does_not_stop_the_run(self):
        def capture(command, **kwargs):
            raise OSError("docker gone")

        sampler = resource_sampling.Sampler(capture, {"mysql": "id-m"}, interval=0.01)
        sampler.start()
        while sampler.failures < 2:
            pass
        self.assertEqual(sampler.stop(), [])


class DiskTest(unittest.TestCase):
    def test_disk_reads_the_data_paths_of_each_service(self):
        commands = []

        def capture(command, **kwargs):
            commands.append(command)
            return "64\t/var/lib/mysql"

        disk = resource_sampling.disk_kib(capture, {"mysql": "id-m"})
        self.assertEqual(disk, {"mysql": 64})
        self.assertEqual(commands[0][:3], ["docker", "exec", "id-m"])
        self.assertIn("/var/lib/mysql", commands[0][-1])

    def test_unreadable_disk_is_none(self):
        def capture(command, **kwargs):
            raise OSError("no du")

        self.assertEqual(resource_sampling.disk_kib(capture, {"mysql": "id-m"}), {"mysql": None})

    def test_growth_is_after_minus_before_and_none_when_either_is_missing(self):
        self.assertEqual(resource_sampling.disk_growth({"mysql": 10, "kafka": None}, {"mysql": 25, "kafka": 4}),
                         {"mysql": {"before_kib": 10, "after_kib": 25, "growth_kib": 15},
                          "kafka": {"before_kib": None, "after_kib": 4, "growth_kib": None}})


if __name__ == "__main__":
    unittest.main()
