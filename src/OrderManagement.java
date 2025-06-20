import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
 * DESIGN AND ARCHITECTURE SUMMARY
 * -------------------------------
 * This order management system handles orders with:
 * - Time-based trading windows (logon/logout messages at window boundaries)
 * - Order throttling (X orders/sec with queuing)
 * - Queue management (modify/cancel operations on queued orders)
 * - Response logging with latency tracking
 * - Thread-safe operations using locks and concurrent collections
 *
 * Key Components:
 * 1. Scheduled tasks for:
 *    - Trading window checks (every minute)
 *    - Throttle reset and queue processing (every second)
 * 2. State management for:
 *    - Trading window status
 *    - Orders sent in current second
 *    - Queued orders (with fast lookup map)
 *    - Sent orders (for response matching)
 *
 * ASSUMPTIONS
 * -----------
 * - Order IDs are unique
 * - System time is reliable (no timezone conversions)
 * - Exchange responses arrive in order
 * - No network failure handling
 * - No third-party libraries used
 */

public class OrderManagement {
    // Configuration
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final int ordersPerSecond;

    // State
    private volatile boolean inTradingWindow = false;
    private final AtomicInteger ordersSentThisSecond = new AtomicInteger(0);
    private final Queue<OrderRequest> orderQueue = new LinkedList<>();
    private final Map<Long, OrderRequest> queuedOrdersMap = new HashMap<>();
    private final Map<Long, Long> sentOrdersMap = new ConcurrentHashMap<>();
    private final Lock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public OrderManagement(LocalTime startTime, LocalTime endTime, int ordersPerSecond) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.ordersPerSecond = ordersPerSecond;

        // Initial trading window check
        checkTradingWindow();

        // Start periodic tasks
        scheduler.scheduleAtFixedRate(this::resetCounterAndSendQueued, 0, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::checkTradingWindow, 0, 1, TimeUnit.MINUTES);
    }

    // Called when an OrderRequest is received
    public void onData(OrderRequest request) {
        if (!inTradingWindow) {
            System.out.println("Order rejected: outside trading window.");
            return;
        }

        lock.lock();
        try {
            switch (request.m_requestType) {
                case New:
                    handleNewOrder(request);
                    break;
                case Modify:
                    handleModify(request);
                    break;
                case Cancel:
                    handleCancel(request);
                    break;
                default:
                    System.out.println("Unknown request type.");
            }
        } finally {
            lock.unlock();
        }
    }

    private void handleNewOrder(OrderRequest request) {
        if (ordersSentThisSecond.get() < ordersPerSecond) {
            ordersSentThisSecond.incrementAndGet();
            send(request);
            sentOrdersMap.put(request.m_orderId, System.currentTimeMillis());
        } else {
            orderQueue.add(request);
            queuedOrdersMap.put(request.m_orderId, request);
        }
    }

    private void handleModify(OrderRequest request) {
        OrderRequest queuedOrder = queuedOrdersMap.get(request.m_orderId);
        if (queuedOrder != null) {
            queuedOrder.m_price = request.m_price;
            queuedOrder.m_qty = request.m_qty;
        }
    }

    private void handleCancel(OrderRequest request) {
        OrderRequest queuedOrder = queuedOrdersMap.remove(request.m_orderId);
        if (queuedOrder != null) {
            orderQueue.remove(queuedOrder);
        }
    }

    // Called when an OrderResponse is received
    public void onData(OrderResponse response) {
        Long sendTime = sentOrdersMap.remove(response.m_orderId);
        if (sendTime != null) {
            long latency = System.currentTimeMillis() - sendTime;
            logResponse(response, latency);
        }
    }

    private void logResponse(OrderResponse response, long latency) {
        System.out.printf("Response: orderId=%d, type=%s, latency=%dms%n",
                response.m_orderId, response.m_responseType, latency);
    }

    // Exchange communication methods
    public void send(OrderRequest request) {
        System.out.println("Sending order: " + request.m_orderId);
    }

    public void sendLogon() {
        System.out.println("Sending logon message.");
    }

    public void sendLogout() {
        System.out.println("Sending logout message.");
    }

    private void resetCounterAndSendQueued() {
        lock.lock();
        try {
            ordersSentThisSecond.set(0);
            int toSend = ordersPerSecond;
            while (toSend > 0 && !orderQueue.isEmpty()) {
                OrderRequest order = orderQueue.poll();
                if (queuedOrdersMap.remove(order.m_orderId) != null) {
                    send(order);
                    sentOrdersMap.put(order.m_orderId, System.currentTimeMillis());
                    ordersSentThisSecond.incrementAndGet();
                    toSend--;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void checkTradingWindow() {
        LocalTime now = LocalTime.now();
        boolean shouldBeInWindow = !now.isBefore(startTime) && !now.isAfter(endTime);

        if (shouldBeInWindow && !inTradingWindow) {
            inTradingWindow = true;
            sendLogon();
        } else if (!shouldBeInWindow && inTradingWindow) {
            inTradingWindow = false;
            sendLogout();
        }
    }

    // Cleanup method
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    // Main method with proper timing
    public static void main(String[] args) throws InterruptedException {
        // Set trading window to current time ±1 hour
        OrderManagement om = new OrderManagement(
                LocalTime.now().minusHours(1),
                LocalTime.now().plusHours(1),
                100
        );

        // Allow time for initial trading window check
        Thread.sleep(50);

        // Create and send test order
        OrderRequest newOrder = new OrderRequest();
        newOrder.m_orderId = 1;
        newOrder.m_requestType = RequestType.New;
        newOrder.m_price = 150.25;
        newOrder.m_qty = 100;
        newOrder.m_side = 'B';
        om.onData(newOrder);

        // Simulate exchange response
        OrderResponse response = new OrderResponse();
        response.m_orderId = 1;
        response.m_responseType = ResponseType.Accept;
        om.onData(response);

        // Cleanup
        Thread.sleep(100); // Allow time for queue processing
        om.shutdown();
    }
}
