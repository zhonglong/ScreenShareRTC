/*
 * Copyright 2014 Pierre Chabardes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.pchab.androidrtc;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.HashMap;
import java.util.LinkedList;

public class WebRtcClient {

    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private final static String TAG = "WebRtcClient";
    private final static int MAX_PEER = 2;
    private boolean[] endPoints = new boolean[MAX_PEER];
    private PeerConnectionFactory factory;
    private HashMap<String, Peer> peers = new HashMap<>();
    private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    private PeerConnectionClient.PeerConnectionParameters mPeerConnParams;
    private MediaConstraints mPeerConnConstraints = new MediaConstraints();
    private MediaStream mLocalMediaStream;
    private VideoSource mVideoSource;
    private RtcListener mListener;
    VideoCapturer videoCapturer;
    MessageHandler messageHandler = new MessageHandler();
    Context mContext;

    /**
     * Implement this interface to be notified of events.
     */
    public interface RtcListener {
        void onReady(String remoteId);

        void onCall(String applicant);

        void onHandup();

        void onStatusChanged(String newStatus);
    }

    public interface Command {
        void execute(String peerId, JSONObject payload) throws JSONException;
    }

    public class CreateOfferCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "CreateOfferCommand");
            Peer peer = peers.get(peerId);
            peer.pc.createOffer(peer, mPeerConnConstraints);
        }
    }

    public class SetRemoteSDPCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "SetRemoteSDPCommand");
            Peer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.optString("type")),
                    payload.optString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
        }
    }

    public class AddIceCandidateCommand implements Command {
        int mLineIndex = 1;
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "AddIceCandidateCommand");
            PeerConnection pc = peers.get(peerId).pc;
            if (pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        payload.optString("mid"),
                        mLineIndex++,
                        payload.optString("candidate")
                );
                pc.addIceCandidate(candidate);
            }
        }
    }

    /**
     * Send a message through the signaling server
     *
     * @param to      id of recipient
     * @param type    type of message
     * @param payload payload of message
     * @throws JSONException
     */
    public void sendMessage(String to, String type, JSONObject message) throws JSONException {
        message.put("remote_user_id", to);
        message.put("type", type);
        mListener.onCall(message.toString());
        Log.d(TAG, "socket send " + type + " to " + to + " payload:" + message);
    }

    public class MessageHandler {
        private HashMap<String, Command> commandMap;

        public MessageHandler() {
            this.commandMap = new HashMap<>();
            commandMap.put("user_join_transmission", new CreateOfferCommand());
            commandMap.put("answer", new SetRemoteSDPCommand());
            commandMap.put("candidate", new AddIceCandidateCommand());
        }

            public void call(String from, JSONObject payload) {
                try {
                    String type = payload.optString("type");
                    Log.d(TAG, "socket received " + type + " from " + from);
                    // if peer is unknown, try to add him
                    if (!peers.containsKey(from)) {
                        // if MAX_PEER is reach, ignore the call
                        int endPoint = findEndPoint();
                        if (endPoint != MAX_PEER) {
                            Peer peer = addPeer(from, endPoint);
                            peer.pc.addStream(mLocalMediaStream);
                            commandMap.get(type).execute(from, payload);
                        }
                    } else {
                        Command command = commandMap.get(type);
                        if(command!=null){
                            command.execute(from, payload);
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
    }

    public class Peer implements SdpObserver, PeerConnection.Observer {
        public PeerConnection pc;
        public String id;
        public int endPoint;

        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
            // TODO: modify sdp to use mPeerConnParams prefered codecs
            try {
                JSONObject payload = new JSONObject();
                payload.put("sdp", sdp.description);
                Log.d(TAG, "onCreateSuccess");
                sendMessage(id, sdp.type.canonicalForm(), payload);
                pc.setLocalDescription(Peer.this, sdp);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }


        public void onIceConnectionReceivingChange(boolean var1) {

        }

        public void onIceCandidatesRemoved(IceCandidate[] var1) {

        }

        public void onAddTrack(RtpReceiver var1, MediaStream[] var2) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id);
                mListener.onStatusChanged("DISCONNECTED");
            }
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }

        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("mid", candidate.sdpMid);
                payload.put("candidate", candidate.sdp);
                sendMessage(id, "new_candidate_mid", payload);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.label());
            // remote streams are displayed from 1 to MAX_PEER (0 is localStream)
//            mediaStream.videoTracks.get(0).addRenderer(new VideoRenderer(mRemoteRender));
//            mListener.onAddRemoteStream(mediaStream, endPoint + 1);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "onRemoveStream " + mediaStream.label());
            removePeer(id);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        public Peer(String id, int endPoint) {
            Log.d(TAG, "new Peer: " + id + " " + endPoint);
            this.pc = factory.createPeerConnection(iceServers, mPeerConnConstraints, this);
            this.id = id;
            this.endPoint = endPoint;
            pc.addStream(mLocalMediaStream); //, new MediaConstraints()
        }
    }

    private Peer addPeer(String id, int endPoint) {
        Peer peer = new Peer(id, endPoint);
        peers.put(id, peer);

        endPoints[endPoint] = true;
        return peer;
    }

    private void removePeer(String id) {
        Peer peer = peers.get(id);
        peer.pc.close();
        peers.remove(peer.id);
        endPoints[peer.endPoint] = false;
    }

    public WebRtcClient(Context context, RtcListener listener, VideoCapturer capturer, PeerConnectionClient.PeerConnectionParameters params) {
        mContext = context;
        mListener = listener;
        mPeerConnParams = params;
        videoCapturer = capturer;
        PeerConnectionFactory.initializeAndroidGlobals(mContext, false, true,
                params.videoCodecHwAcceleration);
        factory = new PeerConnectionFactory();

        iceServers.add(new PeerConnection.IceServer("stun:rdturn.tpv-tech.com:3478"));
//        iceServers.add(new PeerConnection.IceServer("turn:rdturn.tpv-tech.com:3478"));
        iceServers.add(new PeerConnection.IceServer("stun:rdturn.tpv-tech.com:5349", "aiteam", "1EPhkTamu8X"));
        iceServers.add(new PeerConnection.IceServer("turn:rdturn.tpv-tech.com:5349", "aiteam", "1EPhkTamu8X"));

//        mPeerConnConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
//        mPeerConnConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        mPeerConnConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
    }


    /**
     * Call this method in Activity.onDestroy()
     */
    public void destroy() {
        for (Peer peer : peers.values()) {
            peer.pc.dispose();
        }
        if (factory != null) {
            factory.dispose();
        }
        if (mVideoSource != null) {
            mVideoSource.dispose();
        }
//        mSocket.disconnect();
//        mSocket.close();
    }

    private int findEndPoint() {
        return 0;
    }

    /**
     * Start the mSocket.
     * <p>
     * Set up the local stream and notify the signaling server.
     * Call this method after onCallReady.
     *
     * @param name mSocket name
     */
    public void start(String name) {
        initScreenCapturStream();
    }

    private void initScreenCapturStream() {
        mLocalMediaStream = factory.createLocalMediaStream("ARDAMS");
        MediaConstraints videoConstraints = new MediaConstraints();
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", Integer.toString(mPeerConnParams.videoHeight)));
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", Integer.toString(mPeerConnParams.videoWidth)));
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", Integer.toString(mPeerConnParams.videoFps)));
        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("minFrameRate", Integer.toString(mPeerConnParams.videoFps)));

//        VideoCapturer capturer = createScreenCapturer();
        mVideoSource = factory.createVideoSource(videoCapturer);
        videoCapturer.startCapture(mPeerConnParams.videoWidth, mPeerConnParams.videoHeight, mPeerConnParams.videoFps);
        VideoTrack localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, mVideoSource);
        localVideoTrack.setEnabled(true);
        mLocalMediaStream.addTrack(factory.createVideoTrack("ARDAMSv0", mVideoSource));
//        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
//        mLocalMediaStream.addTrack(factory.createAudioTrack("ARDAMSa0", audioSource));
//        mLocalMediaStream.videoTracks.get(0).addRenderer(new VideoRenderer(mLocalRender));
//        mListener.onLocalStream(mLocalMediaStream);
        mListener.onStatusChanged("STREAMING");
    }

    public void onMessage(String peerId, JSONObject payload) {
        messageHandler.call(peerId, payload);
    }
}
