package org.basex.http.ws;

import java.util.*;
import java.util.function.*;

import javax.servlet.http.*;

import org.basex.http.web.*;
import org.basex.http.ws.stomp.*;
import org.basex.http.ws.stomp.frames.*;
import org.basex.query.ann.*;
import org.basex.query.value.item.*;
import org.basex.util.*;
import org.eclipse.jetty.websocket.api.*;

/**
 * This class defines an abstract WebSocket. It inherits the Jetty WebSocket adapter.
 *
 * @author BaseX Team 2005-18, BSD License
 * @author Johannes Finckh
 */
/*
 * [JF] Fragen: - Wenn HeaderParam der vom XQuery-Nutzer gefordert wird nicht existiert wird fehler
 * geworfen -> eig sollte default verwendet werden? -> Passiert auch unabhängig von STOMP! -> Hier
 * relevant für header wie HOST, ...
 */
public final class StompV12WebSocket extends WebSocket {

  /** Map for mapping stomids to the channels */
  private Map<String, String> StompIdChannel = new HashMap<>();
  /** List of all channels the WebSocket is connected to */
  private List<String> channels = new ArrayList<>();
  /** Map of stompids with ackmode */
  private Map<String, String> stompAck = new HashMap<>();
  /** Map of Transactionids with a List of StompFrames */
  private Map<String, List<StompFrame>> transidStompframe = new HashMap<>();
  /** Timer for HeartBeats */
  private Timer heartbeatTimer;
  /** Checktimer for the Clientheartbeats*/
  private Timer clientHeartbeatCheck;
  /** Time of the last received Message*/
  private LastActivity lastActivity;
  /**
   * Constructor.
   * @param req request
   */
  StompV12WebSocket(final HttpServletRequest req) {
    super(req);
  }

  /**
   * Creates a new WebSocket instance.
   * @param req request
   * @return WebSocket or {@code null}
   */
  static StompV12WebSocket get(final HttpServletRequest req) {
    final StompV12WebSocket ws = new StompV12WebSocket(req);
    try { if(!WebModules.get(ws.context).findWs(ws, null, ws.getPath()).isEmpty()) return ws;}
    catch(final Exception ex) {
      Util.debug(ex);
      throw new CloseException(StatusCode.ABNORMAL, ex.getMessage());
    }
    return null;
  }

  /**
   * Consumer for adding headers.
   */
  final BiConsumer<String, String> addHeader = (k, v) -> {
    if(v != null) headers.put(k, new Atm(v));
  };

  /**
   * Returns the Ack-Mode for the StompId.
   * @param stompId StompId
   * @return String ACK-Mode
   */
  public String getAckMode(final String stompId) {
    return stompAck.get(stompId);
  }

  /**
   * Returns the StompId to a Channel.
   * @param channel Channel
   * @return String the stompId, can be {@code null};
   */
  public String getStompId(final String channel) {
    for(String stompid : StompIdChannel.keySet()) {
      if(StompIdChannel.get(stompid).equals(channel)) return stompid;
    }
    return null;
  }

  @Override
  public void onWebSocketConnect(final Session sess) {
    lastActivity = new LastActivity();
    super.onWebSocketConnect(sess);
  }

  @Override
  public void onWebSocketText(final String message) {
    lastActivity.setLastActivity();
    if(message.equals("\n")) {
      super.getSession().getRemote().sendStringByFuture("\n");
      return;
    }
    StompFrame stompframe = null;
    try {stompframe = parseStompFrame(message);}
    catch(CloseException ce) {
      if(heartbeatTimer != null) heartbeatTimer.cancel();
      if(clientHeartbeatCheck != null) clientHeartbeatCheck.cancel();
      sendError(ce.getMessage());
      return;
    }
    if(stompframe == null) return;
    Map<String, String> stompheaders = stompframe.getHeaders();
    // Add the StompHeaders to the Headers
    stompheaders.forEach(addHeader);
    switch(stompframe.getCommand()) {
      case CONNECT:
      case STOMP:
        Map<String, String> cHeader = new HashMap<>();
        cHeader.put("version", "1.2");

        // Configure Heartbeat
        String heartbeat = stompheaders.get("heart-beat");
        if(heartbeat != null) {
          String[] hbVals = heartbeat.split(",");
          int clientServer = Integer.parseInt(hbVals[0]);
          int serverClient = Integer.parseInt(hbVals[1]);
          cHeader.put("heart-beat",serverClient + "," + clientServer);
          if(serverClient > 0 ) {
            heartbeatTimer = new Timer();
            heartbeatTimer.scheduleAtFixedRate(new HeartBeat(super.getRemote()), serverClient, serverClient);
          }
          if(clientServer > 0) {
            clientHeartbeatCheck = new Timer();
            clientHeartbeatCheck.scheduleAtFixedRate(
                new ClientHeartBeat(super.getSession(),lastActivity,clientServer),
                                    clientServer, clientServer);
          };
        }

        ConnectedFrame cf = new ConnectedFrame(Commands.CONNECTED, cHeader, "");
        super.getSession().getRemote().sendStringByFuture(cf.serializedFrame());
        findAndProcess(Annotation._WS_STOMP_CONNECT, null, null);
        break;
      case SEND:
        if(stompheaders.get("transaction") != null) {
          addMsgToTransaction(stompheaders, stompframe);
        } else {
          String destination = stompheaders.get("destination");
          findAndProcess(Annotation._WS_STOMP_MESSAGE, stompframe.getBody(), destination);
        }
        break;
      case SUBSCRIBE:
        if(channels.contains(stompheaders.get("destination"))) {
          sendError("No Destination found");
          return;
        }
        channels.add(stompheaders.get("destination"));
        StompIdChannel.put(stompheaders.get("id"),
                           stompheaders.get("destination"));
        stompAck.put(stompheaders.get("id"),
                     stompheaders.get("ack"));
        WsPool.get().joinChannel(stompheaders.get("destination"),
                                 id);
        findAndProcess(Annotation._WS_STOMP_SUBSCRIBE, null, stompheaders.get("destination"));
        break;
      case UNSUBSCRIBE:
        String channel = StompIdChannel.get(stompheaders.get("id"));
        if(channel == null) return;
        WsPool.get().leaveChannel(channel, id);
        channels.remove(channel);
        StompIdChannel.remove(stompheaders.get("id"));
        stompAck.remove(stompheaders.get("id"));
        findAndProcess(Annotation._WS_STOMP_UNSUBSCRIBE, null, channel);
        break;
      case ACK:
        if(stompheaders.get("transaction") != null) addMsgToTransaction(stompheaders, stompframe);
        else removeMessages(stompheaders, Annotation._WS_STOMP_NACK);
        break;
      case NACK:
        if(stompheaders.get("transaction") != null) addMsgToTransaction(stompheaders, stompframe);
        else removeMessages(stompheaders, Annotation._WS_STOMP_NACK);
        break;
      case BEGIN:
        transidStompframe.put(stompheaders.get("transaction"), null);
        break;
      case COMMIT:
        commitTransaction(stompheaders.get("transaction"));
        transidStompframe.remove(stompheaders.get("transaction"));
        break;
      case ABORT:
        transidStompframe.remove(stompheaders.get("transaction"));
        break;
      case DISCONNECT:
        if(transidStompframe.containsKey(stompheaders.get("transaction")))
          transidStompframe.remove(stompheaders.get("transaction"));

        Map<String, String> ch = new HashMap<>();
        ch.put("receipt-id", stompheaders.get("receipt"));
        ReceiptFrame rf = new ReceiptFrame(Commands.RECEIPT, ch, "");
        if(heartbeatTimer != null) heartbeatTimer.cancel();
        if(clientHeartbeatCheck != null) clientHeartbeatCheck.cancel();
        super.getSession().getRemote().sendStringByFuture(rf.serializedFrame());
        break;
      default:
        break;
    }
    for(String headername : stompheaders.keySet())
      headers.remove(headername);
    headers.remove("messageid");
    headers.remove("message");
    headers.remove("wsid");
  }

  @Override
  public void onWebSocketError(final Throwable cause) {
    if(heartbeatTimer != null) heartbeatTimer.cancel();
    if(clientHeartbeatCheck != null) clientHeartbeatCheck.cancel();
    super.onWebSocketError(cause);
  }

  @Override
  public void onWebSocketClose(final int status, final String message) {
    if(heartbeatTimer != null) heartbeatTimer.cancel();
    if(clientHeartbeatCheck != null) clientHeartbeatCheck.cancel();
    super.onWebSocketClose(status, message);
  }

  /**
   * Parses a Stringmessage to a StompFrame.
   * @param message String
   * @throws CloseException close exception
   * @return the StompFrame
   */
  private StompFrame parseStompFrame(final String message) {
    StompFrame stompframe = null;
    try {stompframe = StompFrame.parse(message);}
    catch(HeadersException e) {
      Util.debug(e);
      if(heartbeatTimer != null) heartbeatTimer.cancel();
      if(clientHeartbeatCheck != null) clientHeartbeatCheck.cancel();
      throw new CloseException(StatusCode.ABNORMAL, e.getMessage());
    }
    return stompframe;
  }

  /**
   * Finds a function and processes it.
   * @param ann annotation
   * @param message message (can be {@code null}; otherwise string or byte array)
   * @param wpath The WebSocketFunctionPath (can be {@code null}; if null, use path of the
   *          websocket)
   */
  private void findAndProcess(final Annotation ann, final Object message, final String wpath) {
    String wspath = wpath == null ? this.getPath() : wpath;
    // check if an HTTP session exists, and if it still valid
    try {if(session != null) session.getCreationTime();}
    catch(final IllegalStateException ex) {session = null;}

    try {
      // find function to evaluate
      final WsFunction func = WebModules.get(context).websocket(this, ann, wspath);
      if(func != null) new StompResponse(this).create(func, message);
    }
    catch(final RuntimeException ex) {throw ex;}
    catch(final Exception ex) {
      Util.debug(ex);
      if(heartbeatTimer != null) heartbeatTimer.cancel();
      if(clientHeartbeatCheck != null) clientHeartbeatCheck.cancel();
      throw new CloseException(StatusCode.ABNORMAL, ex.getMessage());
    }
  }

  /**
   * Removes one or many Messages from pending mesasge list. (ackMode=client: current message +
   * older messages, ackMode=client-individual: current message)
   * @param stompheaders stompheaders of the ACK-Frame
   * @param ann Annotation
   */
  private void removeMessages(final Map<String, String> stompheaders, Annotation ann) {
    String ackMode = getAckMode(WsPool.get().getStompIdToMessageId(stompheaders.get("id")));
    SortedSet<MessageObject> ackedMessages = null;
    if(ackMode.equals("client"))
      ackedMessages = WsPool.get().removeMessagesFromNotAcked(id,stompheaders.get("id"));
    else if(ackMode.equals("client-individual")) {
      ackedMessages = new TreeSet<>();
      ackedMessages.add(WsPool.get().removeMessageFromNotAcked(id, stompheaders.get("id")));
    } else return;

    for(Iterator<MessageObject> it = ackedMessages.iterator();it.hasNext();) {
      MessageObject mo = it.next();
      addHeader.accept("messageid", mo.getMessageId());
      addHeader.accept("message", mo.getMessage());
      addHeader.accept("wsid", mo.getWebSocketId());
      findAndProcess(ann, null, null);
    }
  }

  /**
   * Adds a Message to a specific Transaction.
   * @param stompheaders Stompheaders
   * @param stompframe StomFrame
   */
  private void addMsgToTransaction(final Map<String, String> stompheaders,
      final StompFrame stompframe) {
    if(!transidStompframe.containsKey(stompheaders.get("transaction"))) {
      sendError("No Transaction found");
      return;
    }
    List<StompFrame> sf = transidStompframe.get(stompheaders.get("transaction"));
    if(sf == null) {
      sf = new ArrayList<>();
      transidStompframe.put(stompheaders.get("transaction"), sf);
    }
    sf.add(stompframe);
  }

  /**
   * Executes all StompFrames in the Transaction
   * @param transactionId The id of the Transaction
   */
  private void commitTransaction(final String transactionId) {
    List<StompFrame> frames = transidStompframe.get(transactionId);
    if(frames == null) return;
    for(StompFrame stompframe : frames) {
      Map<String, String> stompheaders = stompframe.getHeaders();
      stompheaders.forEach(addHeader);
      switch(stompframe.getCommand()) {
        case SEND:
          findAndProcess(Annotation._WS_STOMP_MESSAGE, stompframe.getBody(),
              stompheaders.get("destination"));
          break;
        case ACK:
          removeMessages(stompheaders, Annotation._WS_STOMP_ACK);
          break;
        case NACK:
          removeMessages(stompheaders, Annotation._WS_STOMP_NACK);
          break;
        default:
          break;
      }
      for(String headername : stompheaders.keySet()) {
        headers.remove(headername);
      }
      headers.remove("messageid");
      headers.remove("message");
      headers.remove("wsid");
    }
  }

  /**
   * Sends an Errorframe
   * @param message error message
   */
  private void sendError(final String message) {
    Map<String, String> cheaders = new HashMap<>();
    cheaders.put("message", message);
    ErrorFrame ef = new ErrorFrame(Commands.ERROR, cheaders, "");
    super.getSession().getRemote().sendStringByFuture(ef.serializedFrame());
  }
}
