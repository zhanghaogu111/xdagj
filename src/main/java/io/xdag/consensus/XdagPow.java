/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.consensus;

import static io.xdag.utils.FastByteComparisons.compareTo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.xdag.core.*;

import io.xdag.crypto.Sha256Hash;
import io.xdag.libp2p.manager.ChannelManager;
import io.xdag.randomx.RandomX;
import io.xdag.randomx.RandomXMemory;
import org.apache.commons.lang3.RandomUtils;
import io.xdag.Kernel;
import io.xdag.mine.MinerChannel;
import io.xdag.mine.manager.AwardManager;
import io.xdag.mine.manager.MinerManager;
import io.xdag.mine.miner.MinerCalculate;
import io.xdag.net.manager.XdagChannelManager;
import io.xdag.net.message.Message;
import io.xdag.utils.XdagSha256Digest;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;

@Slf4j
public class XdagPow implements PoW, Runnable {

    protected BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    protected Timer timer;
    protected Broadcaster broadcaster;

    // 当前区块
    protected Block generateBlock;

    protected byte[] minShare;
    protected byte[] minHash;

    protected XdagChannelManager channelMgr;
    protected Blockchain blockchain;

    protected Task currentTask;
    protected long taskIndex = 0;

    /** 存放的是过去十六个区块的hash */
    protected List<byte[]> blockHashs = new CopyOnWriteArrayList<byte[]>();
    /** 存放的是最小的hash */
    protected List<byte[]> minShares = new CopyOnWriteArrayList<>(new ArrayList<>(16));
    /** 引入矿工与奖励 */
    protected AwardManager awardManager;
    protected MinerManager minerManager;


    private Kernel kernel;

    private boolean isRunning = false;

    private ChannelManager channelManager;

    protected RandomX randomXUtils;

    public XdagPow(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.channelMgr = kernel.getChannelMgr();
        this.timer = new Timer();
        this.broadcaster = new Broadcaster();
        this.minerManager = kernel.getMinerManager();
        this.awardManager = kernel.getAwardManager();
        this.channelManager = kernel.getChannelManager();

        this.randomXUtils = kernel.getRandomXUtils();
    }

    @Override
    public void start() {
        if (!this.isRunning) {
            this.isRunning = true;
            this.minerManager = kernel.getMinerManager();
            // 容器的初始化
            for (int i = 0; i < 16; i++) {
                this.blockHashs.add(null);
                this.minShares.add(null);
            }

            // PoW换成Schedule任务
            new Thread(this, "xdag-pow-main").start();
            new Thread(this.timer, "xdag-pow-timer").start();
            new Thread(this.broadcaster, "xdag-pow-broadcaster").start();
        }
    }

    @Override
    public void stop() {
        if (this.isRunning) {
            this.isRunning = false;
            this.timer.isRunning = false;
            this.broadcaster.isRunning = false;
        }
    }

    public void newBlock() {
        log.debug("Start new block generate....");
        long sendTime = XdagTime.getMainTime();
        resetTimeout(sendTime);

        if (randomXUtils.isRandomxFork(XdagTime.getEpoch(sendTime))) {
            if (randomXUtils.getRandomXPoolMemIndex() == 0) {
                randomXUtils.setRandomXPoolMemIndex( (randomXUtils.getRandomXHashEpochIndex() - 1) & 1);
            }
            long randomXMemIndex = randomXUtils.getRandomXPoolMemIndex() + 1;
            RandomXMemory memory = randomXUtils.getGlobalMemory()[(int)(randomXMemIndex) & 1];

            if(( XdagTime.getEpoch(XdagTime.getMainTime()) >= memory.getSwitchTime() ) && (memory.getIsSwitched() == 0)) {
                randomXUtils.setRandomXPoolMemIndex(randomXUtils.getRandomXPoolMemIndex()+1);
                memory.setIsSwitched(1);
            }

            generateBlock = generateRandomXBlock(sendTime);
        } else {
            generateBlock = generateBlock(sendTime);
        }
    }


    public Block generateRandomXBlock(long sendTime) {
        log.debug("Generate RandomX block...");
        // 新增任务
        taskIndex++;

        // 固定sendtime
        Block block = blockchain.createNewBlock(null, null, true, null);
        block.signOut(kernel.getWallet().getDefKey().ecKey);

        // 随机
        minShare = RandomUtils.nextBytes(32);
        block.setNonce(minShare);

        // 设置最小hash
        minHash = Hex.decode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

        //
        currentTask = createTaskByRandomXBlock(block, sendTime);
        log.debug("Send Task to Miners");

        // 更新poolminer的贡献
        minerManager.updateNewTaskandBroadcast(currentTask);
        awardManager.onNewTask(currentTask);

//        MinerCalculate.calculateNopaidShares(
//                awardManager.getPoolMiner(), minHash, block.getTimestamp());

        return block;
    }

    public Block generateBlock(long sendTime) {
        // 新增任务
        taskIndex++;

        // 新建区块
        Block block = blockchain.createNewBlock(null, null, true, null);
        block.signOut(kernel.getWallet().getDefKey().ecKey);

        minShare = RandomUtils.nextBytes(32);
        block.setNonce(minShare);

        // 初始nonce, 计算minhash但不作为最终hash
        minHash = block.recalcHash();

        // 发送给矿工
        currentTask = createTaskByNewBlock(block,sendTime);

        log.debug("Send Task to Miners");

        // 更新poolminer的贡献
        minerManager.updateNewTaskandBroadcast(currentTask);
        awardManager.onNewTask(currentTask);

        MinerCalculate.calculateNopaidShares(
                awardManager.getPoolMiner(), minHash, block.getTimestamp());

        return block;
    }

    protected void resetTimeout(long timeout) {
        timer.timeout(timeout);
        events.removeIf(e -> e.type == Event.Type.TIMEOUT);
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    /** 每收到一个miner的信息 之后都会在这里进行一次计算 */
    @Override
    public void receiveNewShare(MinerChannel channel, Message msg) {
        log.debug("Receive share From PoolChannel");
        if (!this.isRunning) {
            return;
        }

        XdagField shareInfo = new XdagField(msg.getEncoded());
        log.debug("shareinfo:" + Hex.toHexString(shareInfo.getData()));
        events.add(new Event(Event.Type.NEW_SHARE, shareInfo, channel));
    }

    @Override
    public void receiveNewPretop(byte[] pretop) {
        log.debug("ReceiveNewPretop");
        if (!this.isRunning) {
            return;
        }
        events.add(new Event(Event.Type.NEW_PRETOP, pretop));
    }

    protected void onNewShare(XdagField shareInfo, MinerChannel channel) {
        try {
            log.debug("On new share...");
            byte[] hash;
            // if randomx fork
            if (kernel.getRandomXUtils().isRandomxFork(currentTask.getTaskTime())) {
                byte[] taskData = new byte[64];
                System.arraycopy(currentTask.getTask()[0].getData(),0,taskData,0,32);
                System.arraycopy(Arrays.reverse(shareInfo.getData()),0,taskData,32,32);
                hash = Arrays.reverse(kernel.getRandomXUtils().randomXPoolCalcHash(taskData, taskData.length, currentTask.getTaskTime()));
            } else{
                XdagSha256Digest digest = new XdagSha256Digest(currentTask.getDigest());
                hash = digest.sha256Final(Arrays.reverse(shareInfo.getData()));
            }
            MinerCalculate.updateMeanLogDiff(channel, currentTask, hash);
            MinerCalculate.calculateNopaidShares(channel, hash, currentTask.getTaskTime());

            if (compareTo(hash, 0, 32, minHash, 0, 32) < 0) {
                minHash = hash;
                minShare = Arrays.reverse(shareInfo.getData());

                // 把计算出来的最后的结果放到nonce里面
                generateBlock.setNonce(minShare);

                int index = (int) ((currentTask.getTaskTime() >> 16) & 0xf);
                // int index = (int) ((currentTask.getTaskTime() >> 16) & 7);
                minShares.set(index, minShare);
                blockHashs.set(index, minHash);

                log.debug("New MinHash :" + Hex.toHexString(minHash));
                log.debug("New MinShare :" + Hex.toHexString(minShare));

            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    protected void onTimeout() {
        if (generateBlock != null) {
            log.info("Broadcast locally generated blockchain, waiting to be verified. block hash = [{}]",
                    Hex.toHexString(generateBlock.getHash()));
            // 发送区块 如果有的话 然后开始生成新区块
            log.debug("添加并发送现有区块 开始生成新区块 sendTime:" + Long.toHexString(generateBlock.getTimestamp()));
            log.debug("End Time:" + Long.toHexString(XdagTime.getCurrentTimestamp()));
            log.debug("发送区块:" + Hex.toHexString(generateBlock.toBytes()));
            log.debug("发送区块hash:" + Hex.toHexString(generateBlock.getHashLow()));
            log.debug("发送区块hash:" + Hex.toHexString(generateBlock.getHash()));
            kernel.getBlockchain().tryToConnect(new Block(new XdagBlock(generateBlock.toBytes())));
            awardManager.payAndaddNewAwardBlock(minShare.clone(), generateBlock.getHash().clone(),
                    generateBlock.getTimestamp());
            BlockWrapper bw = new BlockWrapper(new Block(new XdagBlock(generateBlock.toBytes())), kernel.getConfig().getTTL());

            broadcaster.broadcast(bw);
        }
        newBlock();
    }

    protected void onNewPreTop() {
        log.debug("Receive New PreTop");
        newBlock();
    }

    public Task createTaskByRandomXBlock(Block block, long sendTime) {
        Task newTask = new Task();
        XdagField[] task = new XdagField[2];

        RandomXMemory memory = randomXUtils.getGlobalMemory()[(int) randomXUtils.getRandomXPoolMemIndex() & 1];

        byte[] rxHash = Sha256Hash.hash(block.getXdagBlock().getData(),0,480);


        // todo
        task[0] = new XdagField(rxHash);
        task[1] = new XdagField(memory.getSeed().clone());

        newTask.setTask(task);
        newTask.setTaskTime(XdagTime.getEpoch(sendTime));
        newTask.setTaskIndex(taskIndex);

        return newTask;
    }

    public Task createTaskByNewBlock(Block block, long sendTime) {
        Task newTask = new Task();

        XdagField[] task = new XdagField[2];
        task[1] = block.getXdagBlock().getField(14);
        byte[] data = new byte[448];

        System.arraycopy(block.getXdagBlock().getData(), 0, data, 0, 448);

        XdagSha256Digest currentTaskDigest = new XdagSha256Digest();
        try {
            currentTaskDigest.sha256Update(data);
            byte[] state = currentTaskDigest.getState();
            task[0] = new XdagField(state);
            currentTaskDigest.sha256Update(block.getXdagBlock().getField(14).getData());
        } catch (IOException e) {
            e.printStackTrace();
        }
        newTask.setTask(task);
        newTask.setTaskTime(XdagTime.getEpoch(sendTime));
        newTask.setTaskIndex(taskIndex);
        newTask.setDigest(currentTaskDigest);
        return newTask;
    }

    @Override
    public void run() {
        resetTimeout(XdagTime.getMainTime());
        while (this.isRunning) {
            try {
                Event ev = events.poll(10, TimeUnit.MILLISECONDS);
                if(ev == null) {
                    continue;
                }
                switch (ev.getType()) {
                case NEW_DIFF:
                    break;
                case NEW_SHARE:
                    onNewShare(ev.getData(), ev.getChannel());
                    break;
                case TIMEOUT:
                    // TODO : 判断当前是否可以进行产块
//                    if(kernel.getXdagState().equals(XdagState.STST) || kernel.getXdagState().equals(XdagState.SYNC)) {
                        onTimeout();
//                    }
                    break;
                case NEW_PRETOP:
                    onNewPreTop();
                    break;
                default:
                    break;
                }
            } catch (InterruptedException e) {
                log.debug(e.getMessage(), e);
            }
        }
    }

    public static class Event {
        private final Type type;
        private final Object data;
        private Object channel;

        public Event(Type type) {
            this(type, null);
        }

        public Event(Type type, Object data) {
            this.type = type;
            this.data = data;
        }

        public Event(Type type, Object data, Object channel) {
            this.type = type;
            this.data = data;
            this.channel = channel;
        }

        public Type getType() {
            return type;
        }

        @SuppressWarnings("unchecked")
        public <T> T getData() {
            return (T) data;
        }

        @SuppressWarnings("unchecked")
        public <T> T getChannel() {
            return (T) channel;
        }

        @Override
        public String toString() {
            return "Event [type=" + type + ", data=" + data + "]";
        }

        public enum Type {
            /** Received a timeout signal. */
            TIMEOUT,
            /** Received a new share message. */
            NEW_SHARE,
            /** Received a new pretop message. */
            NEW_PRETOP,
            /** Received a new largest diff message. */
            NEW_DIFF,
        }
    }

    // TODO: change to scheduleAtFixRate
    public class Timer implements Runnable {
        private long timeout;
        private boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            while (this.isRunning) {
                if (timeout != -1 && XdagTime.getCurrentTimestamp() > timeout) {
                    events.add(new Event(Event.Type.TIMEOUT));
                    timeout = -1;
                    continue;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        public void timeout(long sendtime) {
            if (sendtime < 0) {
                throw new IllegalArgumentException("Timeout can not be negative");
            }
            this.timeout = sendtime;
        }
    }

    public class Broadcaster implements Runnable {
        private final LinkedBlockingQueue<BlockWrapper> queue = new LinkedBlockingQueue<>();
        private boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            while (this.isRunning) {
                BlockWrapper bw = null;
                try {
                    bw = queue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
                if(bw != null) {
                    log.debug("queue take hash[{}]", Hex.toHexString(bw.getBlock().getHash()));
                    log.debug("queue take block date [{}]", Hex.toHexString(bw.getBlock().getHash()));
                    channelMgr.sendNewBlock(bw);
                    channelManager.sendNewBlock(bw);

                }
            }
        }

        public void broadcast(BlockWrapper bw) {
            if (!queue.offer(bw)) {
                log.error("Failed to add a message to the broadcast queue: block = {}", Hex.toHexString(bw.getBlock()
                        .getHash()));
            }
        }
    }
}
