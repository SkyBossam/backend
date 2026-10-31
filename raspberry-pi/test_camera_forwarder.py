import json
import time
import unittest
from unittest.mock import Mock, patch
from camera_forwarder import CameraForwarder, frame_time
from aicoo_device import ApiError


class CameraTests(unittest.TestCase):
    def test_stopped_preview_cannot_be_restamped_as_live(self):
        now=time.time()
        self.assertTrue(frame_time({'wall_time_unix':now,'preview_age_s':.1},now))
        for item in ({'wall_time_unix':now-10,'preview_age_s':0}, {'wall_time_unix':now,'preview_age_s':4}, {'wall_time_unix':now+20,'preview_age_s':0}, {'wall_time_unix':now,'preview_age_s':float('nan')}):
            with self.assertRaises(ValueError): frame_time(item,now)

    def test_uses_loopback_existing_camera_and_deduplicates_source_frame(self):
        agent=Mock(); forwarder=CameraForwarder(agent)
        status=json.dumps({'wall_time_unix':time.time(),'preview_age_s':.1}).encode()
        jpeg=b'\xff\xd8fixture\xff\xd9'
        with patch.object(forwarder,'read',side_effect=[status,jpeg,status]) as read:
            forwarder.send_once(); forwarder.send_once()
        self.assertEqual(forwarder.origin,'http://127.0.0.1:8765')
        self.assertEqual(read.call_args_list[1].args[0],'/frame.jpg')
        agent.send_frame.assert_called_once()

    def test_failed_upload_is_not_acknowledged(self):
        agent=Mock(); agent.send_frame.side_effect=ApiError(401,'DEVICE_TOKEN_INVALID')
        forwarder=CameraForwarder(agent)
        status=json.dumps({'wall_time_unix':time.time(),'preview_age_s':.1}).encode()
        with patch.object(forwarder,'read',side_effect=[status,b'\xff\xd8fixture\xff\xd9']):
            with self.assertRaises(ApiError): forwarder.send_once()
        self.assertIsNone(forwarder.previous)


if __name__=='__main__': unittest.main()
