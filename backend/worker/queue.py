import json
import threading

from .log import emit
from .message import Message


class VisibilityLease:
    """Protect every received message, including those waiting behind a slow one."""
    def __init__(self, sqs, queue_url, messages, interval=20):
        self.sqs, self.queue_url, self.interval = sqs, queue_url, interval
        self.pending = {message['ReceiptHandle'] for message in messages}
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def release(self, receipt):
        with self.lock:
            self.pending.discard(receipt)

    def _run(self):
        while not self.stop.wait(self.interval):
            with self.lock:
                receipts = list(self.pending)
            for receipt in receipts:
                if self.stop.is_set():
                    return
                try:
                    self.sqs.change_message_visibility(QueueUrl=self.queue_url,
                                                       ReceiptHandle=receipt, VisibilityTimeout=60)
                except Exception as error:
                    emit('visibility_extension_failed', error_type=type(error).__name__)

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=1)


class Worker:
    def __init__(self, sqs, s3, queue_url, raw_bucket, processor):
        self.sqs, self.s3 = sqs, s3
        self.queue_url, self.raw_bucket = queue_url, raw_bucket
        self.processor = processor

    def poll(self):
        messages = self.sqs.receive_message(QueueUrl=self.queue_url, MaxNumberOfMessages=10,
                                           WaitTimeSeconds=20, VisibilityTimeout=60).get('Messages', [])
        with VisibilityLease(self.sqs, self.queue_url, messages) as lease:
            for raw in messages:
                try:
                    self.process_message(raw)
                except Exception as error:
                    # No DeleteMessage: SQS retries and eventually moves it to the DLQ.
                    emit('message_failed', error_type=type(error).__name__)
                finally:
                    lease.release(raw['ReceiptHandle'])
        return len(messages)

    def process_message(self, raw):
        message = Message.parse(json.loads(raw['Body']))
        result = self.s3.get_object(Bucket=self.raw_bucket, Key=message.s3_key)
        with result['Body'] as body:
            payload = json.load(body)
        status = self.processor.process(message, payload)
        # process() only returns after the transaction committed successfully.
        self.sqs.delete_message(QueueUrl=self.queue_url, ReceiptHandle=raw['ReceiptHandle'])
        emit('delivery_finished', delivery_id=message.delivery_id, result=status)
