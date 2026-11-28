"""Launch Payment with its verification-only fault fixture (PaymentFaultConfiguration).

Production Payment approves every charge in process (결정 in-process-pg-approval), so a
declined or lost PG answer exists only through this fixture. It is compiled from the
integrationTest source set and never enters the bootJar.
"""
from pathlib import Path

GRADLE_TASK = ":apps:payment:writeVerificationClasspath"
DECLINE = "--stove.verification.decline=true"


def launch(repo):
    classpath = (Path(repo) / "apps/payment/build/verification-classpath.txt").read_text()
    return ["-cp", classpath, "com.stove.payment.PaymentServiceApplication",
            "--spring.main.sources=com.stove.payment.verification.PaymentFaultConfiguration"]
