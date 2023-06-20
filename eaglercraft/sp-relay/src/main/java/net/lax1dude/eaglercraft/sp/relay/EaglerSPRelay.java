package net.lax1dude.eaglercraft.sp.relay;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import net.lax1dude.eaglercraft.sp.relay.RateLimiter.RateLimit;
import net.lax1dude.eaglercraft.sp.relay.pkt.*;
import net.lax1dude.eaglercraft.sp.relay.pkt.IPacket07LocalWorlds.LocalWorld;

public class EaglerSPRelay extends WebSocketServer {

	public static EaglerSPRelay instance;
	public static final EaglerSPRelayConfig config = new EaglerSPRelayConfig();
	
	private static RateLimiter pingRateLimiter = null;
	private static RateLimiter worldRateLimiter = null;
	
	public static final DebugLogger logger = DebugLogger.getLogger("EaglerSPRelay");

	public static void main(String[] args) throws IOException, InterruptedException {
		for(int i = 0; i < args.length; ++i) {
			if(args[i].equalsIgnoreCase("--debug")) {
				DebugLogger.enableDebugLogging(DebugLogger.Level.DEBUG);
				logger.debug("Debug logging enabled");
			}
		}
		
		logger.info("Starting EaglerSPRelay version {}...", Constants.versionName);
		config.load(new File("relayConfig.ini"));
		
		if(config.isPingRateLimitEnable()) {
			pingRateLimiter = new RateLimiter(config.getPingRateLimitPeriod() * 1000,
					config.getPingRateLimitLimit(), config.getPingRateLimitLockoutLimit(),
					config.getPingRateLimitLockoutDuration() * 1000);
		}
		
		if(config.isWorldRateLimitEnable()) {
			worldRateLimiter = new RateLimiter(config.getWorldRateLimitPeriod() * 1000,
					config.getWorldRateLimitLimit(), config.getWorldRateLimitLockoutLimit(),
					config.getWorldRateLimitLockoutDuration() * 1000);
		}
		
		EaglerSPRelayConfigRelayList.loadRelays(new File("relays.txt"));
		
		logger.info("Starting WebSocket Server...");
		instance = new EaglerSPRelay(new InetSocketAddress(config.getAddress(), config.getPort()));
		instance.setConnectionLostTimeout(20);
		instance.setReuseAddr(true);
		instance.start();
		
		Thread tickThread = new Thread((() -> {
			int rateLimitUpdateCounter = 0;
			while(true) {
				try {
					long millis = System.currentTimeMillis();
					synchronized(pendingConnections) {
						Iterator<Entry<WebSocket,PendingConnection>> itr = pendingConnections.entrySet().iterator();
						while(itr.hasNext()) {
							Entry<WebSocket,PendingConnection> etr = itr.next();
							if(millis - etr.getValue().openTime > 500l) {
								etr.getKey().close();
								itr.remove();
							}
						}
					}
					synchronized(clientConnections) {
						Iterator<EaglerSPClient> itr = clientConnections.values().iterator();
						while(itr.hasNext()) {
							EaglerSPClient cl = itr.next();
							if(millis - cl.createdOn > 10000l) {
								cl.disconnect(IPacketFEDisconnectClient.TYPE_TIMEOUT, "Took too long to connect!");
							}
						}
					}
					if(++rateLimitUpdateCounter > 300) {
						if(pingRateLimiter != null) {
							pingRateLimiter.update();
						}
						if(worldRateLimiter != null) {
							worldRateLimiter.update();
						}
						rateLimitUpdateCounter = 0;
					}
				}catch(Throwable t) {
					logger.error("Error in update loop!");
					logger.error(t);
				}
				Util.sleep(100l);
			}
		}), "Relay Tick");
		tickThread.setDaemon(true);
		tickThread.start();
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String s;
		while((s = reader.readLine()) != null) {
			s = s.trim();
			if(s.equalsIgnoreCase("stop") || s.equalsIgnoreCase("end")) {
				logger.info("Shutting down...");
				instance.stop();
				System.exit(0);
			}else if(s.equalsIgnoreCase("reset")) {
				logger.info("Clearing all ratelimits");
				if(pingRateLimiter != null) pingRateLimiter.reset();
				if(worldRateLimiter != null) worldRateLimiter.reset();
			}else {
				logger.info("Unknown command: {}", s);
				logger.info("Type 'stop' to exit" + ((worldRateLimiter != null || pingRateLimiter != null) ? ", 'reset' to clear ratelimits" : ""));
			}
		}
		
	}
	
	private EaglerSPRelay(InetSocketAddress addr) {
		super(addr);
	}
	
	private static class PendingConnection {
		
		private final long openTime;
		private final String address;
		
		public PendingConnection(long openTime, String address) {
			this.openTime = openTime;
			this.address = address;
		}
		
	}

	private static final Map<WebSocket,PendingConnection> pendingConnections = new HashMap();
	private static final Map<String,EaglerSPClient> clientIds = new HashMap();
	private static final Map<WebSocket,EaglerSPClient> clientConnections = new HashMap();
	private static final Map<String,EaglerSPServer> serverCodes = new HashMap();
	private static final Map<WebSocket,EaglerSPServer> serverConnections = new HashMap();
	private static final Map<String,List<EaglerSPClient>> clientAddressSets = new HashMap();
	private static final Map<String,List<EaglerSPServer>> serverAddressSets = new HashMap();

	@Override
	public void onStart() {
		logger.info("Listening on {}", getAddress());
		logger.info("Type 'stop' to exit" + ((worldRateLimiter != null || pingRateLimiter != null) ? ", 'reset' to clear ratelimits" : ""));
	}
	
	@Override
	public void onOpen(WebSocket arg0, ClientHandshake arg1) {
		if(!config.getIsWhitelisted(arg1.getFieldValue("origin"))) {
			arg0.close();
			return;
		}
		
		String addr;
		long millis = System.currentTimeMillis();
		if(config.isEnableRealIpHeader() && arg1.hasFieldValue(config.getRealIPHeaderName())) {
			addr = arg1.getFieldValue(config.getRealIPHeaderName()).toLowerCase();
		}else {
			addr = arg0.getRemoteSocketAddress().getAddress().getHostAddress().toLowerCase();
		}
		
		int totalCons = 0;
		synchronized(pendingConnections) {
			Iterator<PendingConnection> pendingItr = pendingConnections.values().iterator();
			while(pendingItr.hasNext()) {
				if(pendingItr.next().address.equals(addr)) {
					++totalCons;
				}
			}
		}
		synchronized(clientAddressSets) {
			List<EaglerSPClient> lst = clientAddressSets.get(addr);
			if(lst != null) {
				totalCons += lst.size();
			}
		}
		
		if(totalCons >= config.getConnectionsPerIP()) {
			logger.debug("[{}]: Too many connections are open on this address", (String) arg0.getAttachment());
			arg0.send(IPacketFEDisconnectClient.ratelimitPacketTooMany);
			arg0.close();
			return;
		}
		
		arg0.setAttachment(addr);
		
		PendingConnection waiting = new PendingConnection(millis, addr);
		logger.debug("[{}]: Connection opened", arg0.getRemoteSocketAddress());
		synchronized(pendingConnections) {
			pendingConnections.put(arg0, waiting);
		}
	}

	@Override
	public void onMessage(WebSocket arg0, ByteBuffer arg1) {
		DataInputStream sid = new DataInputStream(new ByteBufferInputStream(arg1));
		PendingConnection waiting;
		synchronized(pendingConnections) {
			waiting = pendingConnections.remove(arg0);
		}
		try {
			IPacket pkt = IPacket.readPacket(sid);
			if(waiting != null) {
				if(pkt instanceof IPacket00Handshake) {
					IPacket00Handshake ipkt = (IPacket00Handshake)pkt;
					if(ipkt.connectionVersion != Constants.protocolVersion) {
						logger.debug("[{}]: Connected with unsupported protocol version: {} (supported "
								+ "version: {})", (String) arg0.getAttachment(), ipkt.connectionVersion, Constants.protocolVersion);
						if(ipkt.connectionVersion < Constants.protocolVersion) {
							arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_PROTOCOL_VERSION,
									"Outdated Client! (v" + Constants.protocolVersion + " req)")));
						}else {
							arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_PROTOCOL_VERSION,
									"Outdated Server! (still on v" + Constants.protocolVersion + ")")));
						}
						arg0.close();
						return;
					}
					if(ipkt.connectionType == 0x01) {
						if(!rateLimit(worldRateLimiter, arg0, waiting.address)) {
							logger.debug("[{}]: Got world ratelimited", (String) arg0.getAttachment());
							return;
						}
						synchronized(serverAddressSets) {
							List<EaglerSPServer> lst = serverAddressSets.get(waiting.address);
							if(lst != null) {
								if(lst.size() >= config.getWorldsPerIP()) {
									logger.debug("[{}]: Too many worlds are open on this address", (String) arg0.getAttachment());
									arg0.send(IPacketFEDisconnectClient.ratelimitPacketTooMany);
									arg0.close();
									return;
								}
							}
						}
						logger.debug("[{}]: Connected as a server", (String) arg0.getAttachment());
						EaglerSPServer srv;
						synchronized(serverCodes) {
							int j = 0;
							String code;
							do {
								if(++j > 100) {
									logger.error("Error: relay is running out of codes!");
									logger.error("Closing connection to {}", (String) arg0.getAttachment());
									arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_INTERNAL_ERROR,
											"Internal Server Error")));
									arg0.close();
									return;
								}
								code = config.generateCode();
							}while(serverCodes.containsKey(code));
							srv = new EaglerSPServer(arg0, code, ipkt.connectionCode, waiting.address);
							serverCodes.put(code, srv);
							ipkt.connectionCode = code;
							arg0.send(IPacket.writePacket(ipkt));
							logger.debug("[{}][Relay -> Server] PKT 0x00: Assign join code: {}", (String) arg0.getAttachment(), code);
						}
						synchronized(serverConnections) {
							serverConnections.put(arg0, srv);
						}
						synchronized(serverAddressSets) {
							List<EaglerSPServer> lst = serverAddressSets.get(srv.serverAddress);
							if(lst == null) {
								lst = new ArrayList();
								serverAddressSets.put(srv.serverAddress, lst);
							}
							lst.add(srv);
						}
						srv.send(new IPacket01ICEServers(EaglerSPRelayConfigRelayList.relayServers));
						logger.debug("[{}][Relay -> Server] PKT 0x01: Send ICE server list to server", (String) arg0.getAttachment());
					}else {
						if(!rateLimit(pingRateLimiter, arg0, waiting.address)) {
							logger.debug("[{}]: Got ping ratelimited", (String) arg0.getAttachment());
							return;
						}
						if(ipkt.connectionType == 0x02) {
							String code = ipkt.connectionCode;
							logger.debug("[{}]: Connected as a client, requested server code: {}", (String) arg0.getAttachment(), code);
							if(code.length() != config.getCodeLength()) {
								logger.debug("The code '{}' is invalid because it's the wrong length, disconnecting", code);
								arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_CODE_LENGTH,
										"The join code is the wrong length, it should be " + config.getCodeLength() + " chars long")));
								arg0.close();
							}else {
								if(!config.isCodeMixCase()) {
									code = code.toLowerCase();
								}
								EaglerSPServer srv;
								synchronized(serverCodes) {
									srv = serverCodes.get(code);
								}
								if(srv == null) {
									arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_INCORRECT_CODE,
											"Invalid code, no LAN world found!")));
									arg0.close();
									return;
								}
								String id;
								EaglerSPClient cl;
								synchronized(clientIds) {
									int j = 0;
									do {
										id = EaglerSPClient.generateClientId();
									}while(clientIds.containsKey(id));
									cl = new EaglerSPClient(arg0, srv, id, waiting.address);
									clientIds.put(id, cl);
									ipkt.connectionCode = id;
									arg0.send(IPacket.writePacket(ipkt));
									srv.handleNewClient(cl);
								}
								synchronized(clientConnections) {
									clientConnections.put(arg0, cl);
								}
								synchronized(clientAddressSets) {
									List<EaglerSPClient> lst = clientAddressSets.get(cl.address);
									if(lst == null) {
										lst = new ArrayList();
										clientAddressSets.put(cl.address, lst);
									}
									lst.add(cl);
								}
								cl.send(new IPacket01ICEServers(EaglerSPRelayConfigRelayList.relayServers));
								logger.debug("[{}][Relay -> Client] PKT 0x01: Send ICE server list to client", (String) arg0.getAttachment());
							}
						}else if(ipkt.connectionType == 0x03) {
							logger.debug("[{}]: Pinging the server", (String) arg0.getAttachment());
							arg0.send(IPacket.writePacket(new IPacket69Pong(Constants.protocolVersion, config.getComment(), Constants.versionBrand)));
							arg0.close();
						}else if(ipkt.connectionType == 0x04) {
							logger.debug("[{}]: Polling the server for other worlds", (String) arg0.getAttachment());
							if(config.isEnableShowLocals()) {
								arg0.send(IPacket.writePacket(new IPacket07LocalWorlds(getLocalWorlds(waiting.address))));
							}else {
								arg0.send(IPacket.writePacket(new IPacket07LocalWorlds(null)));
							}
							arg0.close();
						}else {
							logger.debug("[{}]: Unknown connection type: {}", (String) arg0.getAttachment(), ipkt.connectionType);
							arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_ILLEGAL_OPERATION,
									"Unexpected Init Packet")));
							arg0.close();
						}
					}
				}else {
					logger.debug("[{}]: Pending connection did not send a 0x00 packet to identify "
							+ "as a client or server", (String) arg0.getAttachment());
					arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_ILLEGAL_OPERATION,
							"Unexpected Init Packet")));
					arg0.close();
				}
			}else {
				EaglerSPServer srv;
				synchronized(serverConnections) {
					srv = serverConnections.get(arg0);
				}
				if(srv != null) {
					if(!srv.handle(pkt)) {
						logger.debug("[{}]: Server sent invalid packet: {}", (String) arg0.getAttachment(), pkt.getClass().getSimpleName());
						arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_INVALID_PACKET,
								"Invalid Packet Recieved")));
						arg0.close();
					}
				}else {
					EaglerSPClient cl;
					synchronized(clientConnections) {
						cl = clientConnections.get(arg0);
					}
					if(cl != null) {
						if(!cl.handle(pkt)) {
							logger.debug("[{}]: Client sent invalid packet: {}", (String) arg0.getAttachment(), pkt.getClass().getSimpleName());
							arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_INVALID_PACKET,
									"Invalid Packet Recieved")));
							arg0.close();
						}
					}else {
						logger.debug("[{}]: Connection has no client/server attached to it!", (String) arg0.getAttachment());
						arg0.send(IPacket.writePacket(new IPacketFFErrorCode(IPacketFFErrorCode.TYPE_ILLEGAL_OPERATION,
								"Internal Server Error")));
						arg0.close();
					}
				}
			}
		}catch(Throwable t) {
			logger.error("[{}]: Failed to handle binary frame: {}", (String) arg0.getAttachment(), t);
			arg0.close();
		}
	}

	@Override
	public void onMessage(WebSocket arg0, String arg1) {
		logger.debug("[{}]: Sent a text frame, disconnecting", (String) arg0.getAttachment());
		arg0.close();
	}

	@Override
	public void onClose(WebSocket arg0, int arg1, String arg2, boolean arg3) {
		EaglerSPServer srv;
		synchronized(serverConnections) {
			srv = serverConnections.remove(arg0);
		}
		if(srv != null) {
			logger.debug("[{}]: Server closed, code: {}", (String) arg0.getAttachment(), srv.code);
			synchronized(serverCodes) {
				serverCodes.remove(srv.code);
			}
			synchronized(serverAddressSets) {
				List<EaglerSPServer> lst = serverAddressSets.get(srv.serverAddress);
				if(lst != null) {
					lst.remove(srv);
					if(lst.size() == 0) {
						serverAddressSets.remove(srv.serverAddress);
					}
				}
			}
			ArrayList<EaglerSPClient> clientList;
			synchronized(clientConnections) {
				clientList = new ArrayList(clientConnections.values());
			}
			Iterator<EaglerSPClient> itr = clientList.iterator();
			while(itr.hasNext()) {
				EaglerSPClient cl = itr.next();
				if(cl.server == srv) {
					logger.debug("[{}]: Disconnecting client: {} (id: ", (String) cl.socket.getAttachment(), cl.id);
					cl.socket.close();
				}
			}
		}else {
			EaglerSPClient cl;
			synchronized(clientConnections) {
				cl = clientConnections.remove(arg0);
			}
			if(cl != null) {
				synchronized(clientAddressSets) {
					List<EaglerSPClient> lst = clientAddressSets.get(cl.address);
					if(lst != null) {
						lst.remove(cl);
						if(lst.size() == 0) {
							clientAddressSets.remove(cl.address);
						}
					}
				}
				logger.debug("[{}]: Client closed, id: {}", (String) arg0.getAttachment(), cl.id);
				synchronized(clientIds) {
					clientIds.remove(cl.id);
				}
				cl.server.handleClientDisconnect(cl);
			}else {
				logger.debug("[{}]: Connection Closed", (String) arg0.getAttachment());
			}
		}
	}

	@Override
	public void onError(WebSocket arg0, Exception arg1) {
		logger.error("[{}]: Exception thrown: {}", (arg0 == null ? "SERVER" : (String) arg0.getAttachment()), arg1.toString());
		logger.debug(arg1);
		arg0.close();
	}
	
	private List<IPacket07LocalWorlds.LocalWorld> getLocalWorlds(String addr) {
		List<IPacket07LocalWorlds.LocalWorld> lst = new ArrayList();
		synchronized(serverAddressSets) {
			List<EaglerSPServer> srvs = serverAddressSets.get(addr);
			if(srvs != null) {
				if(srvs.size() == 0) {
					serverAddressSets.remove(addr);
				}else {
					for(EaglerSPServer s : srvs) {
						if(!s.serverHidden) {
							lst.add(new LocalWorld(s.serverName, s.code));
						}
					}
				}
			}
		}
		return lst;
	}
	
	private boolean rateLimit(RateLimiter limiter, WebSocket sock, String addr) {
		if(limiter != null) {
			RateLimit l = limiter.limit(addr);
			if(l == RateLimit.NONE) {
				return true;
			}else if(l == RateLimit.LIMIT) {
				sock.send(IPacketFEDisconnectClient.ratelimitPacketBlock);
				sock.close();
				return false;
			}else if(l == RateLimit.LIMIT_NOW_LOCKOUT) {
				sock.send(IPacketFEDisconnectClient.ratelimitPacketBlockLock);
				sock.close();
				return false;
			}else if(l == RateLimit.LOCKOUT) {
				sock.close();
				return false;
			}else {
				return true; // ?
			}
		}else {
			return true;
		}
	}

}
