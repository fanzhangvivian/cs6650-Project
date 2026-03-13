package com.chatflow.client.queue;

import com.chatflow.client.model.ChatMessage;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MessageQueue {
    
    private final BlockingQueue<ChatMessage> queue;
    
    public MessageQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }
    
    public void put(ChatMessage message) throws InterruptedException {
        queue.put(message);
    }
    
    public ChatMessage take() throws InterruptedException {
        return queue.take();
    }
    
    /**
     * Poll with timeout - NEW METHOD
     */
    public ChatMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }
    
    public int size() {
        return queue.size();
    }
    
    public boolean isEmpty() {
        return queue.isEmpty();
    }
    
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
}