package com.github.nkzawa.socketio.client;

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.parser.Packet;
import com.github.nkzawa.socketio.parser.Parser;

import java.net.URI;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class Manager extends Emitter {

    private static final Logger logger = Logger.getLogger("socket.io-client:manager");

    /*package*/ static final int CLOSED = 0;
    /*package*/ static final int OPENING = 1;
    /*package*/ static final int OPEN = 2;

    public static final String EVENT_OPEN = "open";
    public static final String EVENT_CLOSE = "close";
    public static final String EVENT_PACKET = "packet";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_CONNECT_ERROR = "connect_error";
    public static final String EVENT_CONNECT_TIMEOUT = "connect_timeout";
    public static final String EVENT_RECONNECT = "reconnect";
    public static final String EVENT_RECONNECT_FAILED = "reconnect_failed";
    public static final String EVENT_RECONNECT_ERROR = "reconnect_error";

    /*package*/ int readyState = CLOSED;

    private boolean _reconnection;
    private boolean skipReconnect;
    private boolean reconnecting;
    private int _reconnectionAttempts;
    private long _reconnectionDelay;
    private long _reconnectionDelayMax;
    private long _timeout;
    private AtomicInteger connected = new AtomicInteger();
    private AtomicInteger attempts = new AtomicInteger();
    private Map<String, Socket> nsps = new ConcurrentHashMap<String, Socket>();
    private Queue<On.Handle> subs = new ConcurrentLinkedQueue<On.Handle>();
    private com.github.nkzawa.engineio.client.Socket engine;

    private ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    public Manager(URI uri, IO.Options opts) {
        opts = initOptions(opts);
        this.engine = new Engine(uri, opts);
    }

    public Manager(com.github.nkzawa.engineio.client.Socket socket, IO.Options opts) {
        opts = initOptions(opts);
        this.engine = socket;
    }

    private IO.Options initOptions(IO.Options opts) {
        if (opts == null) {
            opts = new IO.Options();
        }
        if (opts.path == null) {
            opts.path = "/socket.io";
        }
        this.reconnection(opts.reconnection);
        this.reconnectionAttempts(opts.reconnectionAttempts != 0 ? opts.reconnectionAttempts : Integer.MAX_VALUE);
        this.reconnectionDelay(opts.reconnectionDelay != 0 ? opts.reconnectionDelay : 1000);
        this.reconnectionDelayMax(opts.reconnectionDelayMax != 0 ? opts.reconnectionDelayMax : 5000);
        this.timeout(opts.timeout < 0 ? 10000 : opts.timeout);
        return opts;
    }

    public boolean reconnection() {
        return this._reconnection;
    }

    public Manager reconnection(boolean v) {
        this._reconnection = v;
        return this;
    }

    public int reconnectionAttempts() {
        return this._reconnectionAttempts;
    }

    public Manager reconnectionAttempts(int v) {
        this._reconnectionAttempts = v;
        return this;
    }

    public long reconnectionDelay() {
        return this._reconnectionDelay;
    }

    public Manager reconnectionDelay(long v) {
        this._reconnectionDelay = v;
        return this;
    }

    public long reconnectionDelayMax() {
        return this._reconnectionDelayMax;
    }

    public Manager reconnectionDelayMax(long v) {
        this._reconnectionDelayMax = v;
        return this;
    }

    public long timeout() {
        return this._timeout;
    }

    public Manager timeout(long v) {
        this._timeout = v;
        return this;
    }

    public Manager open(){
        return open(null);
    }

    public Manager open(final OpenCallback fn) {
        if (this.readyState == OPEN && !this.reconnecting) return this;

        final com.github.nkzawa.engineio.client.Socket socket = this.engine;
        final Manager self = this;

        this.readyState = OPENING;

        final On.Handle openSub = On.on(socket, Engine.EVENT_OPEN, new Listener() {
            @Override
            public void call(Object... objects) {
                self.onopen();
                if (fn != null) fn.call(null);
            }
        });

        On.Handle errorSub = On.on(socket, Engine.EVENT_ERROR, new Listener() {
            @Override
            public void call(Object... objects) {
                Object data = objects.length > 0 ? objects[0] : null;
                self.cleanup();
                self.emit(EVENT_CONNECT_ERROR, data);
                if (fn != null) {
                    Exception err = new SocketIOException("Connection error",
                            data instanceof Exception ? (Exception)data : null);
                    fn.call(err);
                }
            }
        });

        if (this._timeout >= 0) {
            final long timeout = this._timeout;
            logger.info(String.format("connection attempt will timeout after %d", timeout));

            final Future timer = timeoutScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    logger.info(String.format("connect attempt timed out after %d", timeout));
                    openSub.destroy();
                    socket.close();
                    socket.emit(Engine.EVENT_ERROR, new SocketIOException("timeout"));
                    self.emit(EVENT_CONNECT_TIMEOUT, timeout);
                }
            }, timeout, TimeUnit.MILLISECONDS);

            On.Handle timeSub = new On.Handle() {
                @Override
                public void destroy() {
                    timer.cancel(false);
                }
            };

            this.subs.add(timeSub);
        }

        this.subs.add(openSub);
        this.subs.add(errorSub);

        this.engine.open();

        return this;
    }

    private void onopen() {
        this.cleanup();

        this.readyState = OPEN;
        this.emit(EVENT_OPEN);

        final com.github.nkzawa.engineio.client.Socket socket = this.engine;
        this.subs.add(On.on(socket, Engine.EVENT_DATA, new Listener() {
            @Override
            public void call(Object... objects) {
                Manager.this.ondata((String)objects[0]);
            }
        }));
        this.subs.add(On.on(socket, Engine.EVENT_ERROR, new Listener() {
            @Override
            public void call(Object... objects) {
                Manager.this.onerror((Exception)objects[0]);
            }
        }));
        this.subs.add(On.on(socket, Engine.EVENT_CLOSE, new Listener() {
            @Override
            public void call(Object... objects) {
                Manager.this.onclose();
            }
        }));
    }

    private void ondata(String data) {
        this.emit(EVENT_PACKET, Parser.decode(data));
    }

    private void onerror(Exception err) {
        this.emit(EVENT_ERROR, err);
    }

    public Socket socket(String nsp) {
        Socket socket = this.nsps.get(nsp);
        if (socket == null) {
            socket = new Socket(this, nsp);
            this.nsps.put(nsp, socket);
            final Manager self = this;
            socket.on(Socket.EVENT_CONNECT, new Listener() {
                @Override
                public void call(Object... objects) {
                    self.connected.incrementAndGet();
                }
            });

        }
        return socket;
    }

    /*package*/ void destroy(Socket socket) {
        int connected = this.connected.decrementAndGet();
        if (connected == 0) {
            this.close();
        }
    }

    /*package*/ void packet(Packet packet) {
        logger.info(String.format("writing packet %s", packet));
        this.engine.write(Parser.encode(packet));
    }

    private void cleanup() {
        On.Handle sub;
        while ((sub = this.subs.poll()) != null) sub.destroy();
    }

    private void close() {
        this.skipReconnect = true;
        this.cleanup();
        this.engine.close();
    }

    private void onclose() {
        this.cleanup();
        if (!this.skipReconnect) {
            this.reconnect();

        }
    }

    private void reconnect() {
        final Manager self = this;
        int attempts = this.attempts.incrementAndGet();

        if (attempts > this._reconnectionAttempts) {
            this.emit(EVENT_RECONNECT_FAILED);
            this.reconnecting = false;
        } else {
            long delay = this.attempts.get() * this.reconnectionDelay();
            delay = Math.min(delay, this.reconnectionDelayMax());
            logger.info(String.format("will wait %dms before reconnect attempt", delay));

            this.reconnecting = true;
            final Future timer = this.reconnectScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    logger.info("attempting reconnect");
                    self.open(new OpenCallback() {
                        @Override
                        public void call(Exception err) {
                            if (err != null) {
                                logger.info("reconnect attempt error");
                                self.reconnect();
                                self.emit(EVENT_RECONNECT_ERROR, err);
                            } else {
                                logger.info("reconnect success");
                                self.onreconnect();
                            }
                        }
                    });
                }
            }, delay, TimeUnit.MILLISECONDS);

            this.subs.add(new On.Handle() {
                @Override
                public void destroy() {
                    timer.cancel(false);
                }
            });
        }
    }

    private void onreconnect() {
        int attempts = this.attempts.get();
        this.attempts.set(0);
        this.reconnecting = false;
        this.emit(EVENT_RECONNECT, attempts);
    }

    public static interface OpenCallback {

        public void call(Exception err);
    }
}
