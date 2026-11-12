import io
import json
import threading
from unittest.mock import Mock

import pytest

from backend.worker.message import Message
from backend.worker.queue import VisibilityLease, Worker


def envelope(**overrides):
    return {'schema_version': 1, 'source': 'webhook', 'delivery_id': 'delivery-1',
            'event': 'installation', 's3_key': '2026-11-03/delivery-1.json',
            'received_at': '2026-11-03T00:00:00Z', **overrides}


def setup_worker(payload=None, processor=None):
    sqs, s3 = Mock(), Mock()
    raw = {'Body': json.dumps(envelope()), 'ReceiptHandle': 'receipt-1'}
    sqs.receive_message.return_value = {'Messages': [raw]}
    s3.get_object.return_value = {'Body': io.BytesIO(json.dumps(payload or {}).encode())}
    worker = Worker(sqs, s3, 'queue', 'raw-bucket', processor or Mock())
    return worker, raw


def test_ack_is_after_success_and_stream_is_closed():
    worker, _ = setup_worker()
    calls = []
    worker.processor.process.side_effect = lambda *args: calls.append('commit') or 'PROCESSED'
    worker.sqs.delete_message.side_effect = lambda **kwargs: calls.append('delete')
    assert worker.poll() == 1
    assert calls == ['commit', 'delete']
    assert worker.s3.get_object.return_value['Body'].closed
    worker.sqs.receive_message.assert_called_once_with(QueueUrl='queue', MaxNumberOfMessages=10,
                                                      WaitTimeSeconds=20, VisibilityTimeout=60)


@pytest.mark.parametrize('failure', ['database', 's3', 'json', 'version'])
def test_failure_is_not_acknowledged_or_logged_with_private_data(failure, capsys):
    worker, raw = setup_worker()
    sensitive = 'secret-token user@example.com private/repo'
    if failure == 'database':
        worker.processor.process.side_effect = RuntimeError(sensitive)
    elif failure == 's3':
        worker.s3.get_object.side_effect = RuntimeError(sensitive)
    elif failure == 'json':
        raw['Body'] = sensitive
    else:
        raw['Body'] = json.dumps(envelope(schema_version=2))
    worker.poll()
    worker.sqs.delete_message.assert_not_called()
    assert sensitive not in capsys.readouterr().out


def test_one_bad_message_does_not_block_the_rest_of_the_batch():
    worker, raw = setup_worker()
    bad = {'Body': '{', 'ReceiptHandle': 'bad-receipt'}
    worker.sqs.receive_message.return_value = {'Messages': [bad, raw]}
    assert worker.poll() == 2
    worker.sqs.delete_message.assert_called_once_with(QueueUrl='queue', ReceiptHandle='receipt-1')


def test_shutdown_leaves_current_message_unacknowledged():
    from backend.worker.__main__ import ShutdownRequested

    worker, _ = setup_worker()
    worker.processor.process.side_effect = ShutdownRequested()
    with pytest.raises(ShutdownRequested):
        worker.poll()
    worker.sqs.delete_message.assert_not_called()


def test_visibility_is_extended_for_messages_waiting_in_the_batch():
    sqs = Mock()
    extended = threading.Event()
    sqs.change_message_visibility.side_effect = lambda **kwargs: extended.set() if kwargs['ReceiptHandle'] == 'second' else None
    with VisibilityLease(sqs, 'queue', [{'ReceiptHandle': 'first'}, {'ReceiptHandle': 'second'}], interval=0.01) as lease:
        assert extended.wait(2)
        lease.release('first')
        lease.release('second')
    assert {call.kwargs['ReceiptHandle'] for call in sqs.change_message_visibility.call_args_list} == {'first', 'second'}


@pytest.mark.parametrize('field,value', [('schema_version', True), ('source', 'other'),
                                       ('delivery_id', 'bad/id'), ('event', 'ping'),
                                       ('s3_key', '../../secret'), ('received_at', '2026-11-03')])
def test_invalid_envelopes_are_rejected(field, value):
    with pytest.raises(ValueError):
        Message.parse(envelope(**{field: value}))


def test_forward_compatible_extra_message_fields_are_allowed():
    assert Message.parse(envelope(extra='allowed')).delivery_id == 'delivery-1'
