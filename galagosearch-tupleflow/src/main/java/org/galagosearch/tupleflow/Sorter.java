// BSD License (http://www.galagosearch.org/license)
package org.galagosearch.tupleflow;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.logging.Logger;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import org.galagosearch.tupleflow.execution.ErrorHandler;
import org.galagosearch.tupleflow.execution.Verification;

/**
 * <p>
 * This class sorts an incoming stream of objects in some specified order.
 * When this object is closed (by calling the close method), the sorted
 * objects are then sent in sorted order to the next stage of the 
 * Processor chain.
 * </p>
 *
 * <p>
 * Since there may be many objects submitted to the Sorter (more than
 * will fit in main memory), the object may create temporary files
 * to store partially sorted results.  The path used for these temporary
 * files is specified in the TempPath Java preferences variable.
 * </p>
 *
 * <p>
 * In many instances, Sorters are used to generate streams of data that
 * are then used to create aggregate statistics.  For instance, suppose
 * we want to compute the monthly sales of a particular corporation, separated
 * by region.  We can feed a set of transactions to the Sorter, each containing
 * a dollar amount and the region it came from, e.g.:
 * <ul>
 *      <li>($5.39, South)</li>
 *      <li>($2.24, North)</li>
 *      <li>($1.50, South)</li>
 * </ul>
 * If we sort this list by region name:
 * <ul>
 *      <li>($2.24, North)</li>
 *      <li>($5.39, South)</li>
 *      <li>($1.50, South)</li>
 * </ul>
 * It's now very easy to add up totals for each region (since all data for each
 * region is adjacent in the list).</p>
 *
 * <p>
 * In these kinds of aggregate applications, it may be more efficient to 
 * provide the Sorter with a Reducer object.  A Reducer is an object that
 * transforms <i>n</i> sorted objects of type T into some (hopefully)
 * smaller number of objects, also of type T.  In the example above, we could
 * write a reducer that turned those three transactions into:
 * <ul>
 *      <li>($2.24, North)</li>
 *      <li>($6.89, South)</li>
 * </ul>
 * which would be equivalent for this application.  Using a Reducer allows
 * the application to buffer fewer items and hopefully reduce the reliance
 * on the disk during sorting.</p>
 *
 * @author Trevor Strohman
 */
public class Sorter<T> extends StandardStep<T, T> implements NotificationListener {
    private int limit;
    private int fileLimit = 20;
    private volatile boolean flushRequested = false;
    private ArrayList<T> objects;
    private ArrayList<List<T>> runs;
    private long runsCount = 0;
    private Logger logger = Logger.getLogger(Sorter.class.toString());
    private ArrayList<File> temporaryFiles;
    private Order<T> order;
    private Comparator<T> lessThanCompare;
    private Reducer<T> reducer;
    private static int reduceInterval = 100 * 1000;
    private static int combineBufferSize = 100 * 1000;
    private static int defaultObjectLimit = 50 * 1000 * 1000;
    private Counter filesWritten = null;
    private Counter sorterCombineSteps;

    public Sorter(Order<T> order) {
        this(defaultObjectLimit, order, null, null);
    }

    public Sorter(Order<T> order, Reducer<T> reducer) {
        this(defaultObjectLimit, order, reducer, null);
    }

    public Sorter(int limit, Order<T> order) {
        this(limit, order, null, null);
    }

    public Sorter(int limit, Order<T> order, Reducer<T> reducer) {
        this(limit, order, reducer, null);
    }

    public Sorter(int limit, Order<T> order, Reducer<T> reducer, Processor<T> processor) {
        this.limit = limit;
        this.order = order;
        this.processor = processor;
        this.reducer = reducer;
        this.objects = new ArrayList<T>();
        this.runs = new ArrayList<List<T>>();
        this.temporaryFiles = new ArrayList<File>();
        this.lessThanCompare = order.lessThan();
        this.flushRequested = false;

        requestMemoryWarnings();
    }

    @SuppressWarnings("unchecked")
    public Sorter(TupleFlowParameters parameters)
            throws ClassNotFoundException, InstantiationException,
                   IllegalAccessException, IOException {
        String className = parameters.getXML().get("class");
        String[] orderSpec = parameters.getXML().get("order").split(" ");

        Class clazz = Class.forName(className);
        Type<T> typeInstance = (Type<T>) clazz.newInstance();
        this.order = typeInstance.getOrder(orderSpec);
        this.limit = (int) parameters.getXML().get("object-limit", defaultObjectLimit);
        this.reducer = null;
        this.flushRequested = false;

        if (parameters.getXML().containsKey("reducer")) {
            Class reducerClass = Class.forName(parameters.getXML().get("reducer"));
            this.reducer = (Reducer<T>) reducerClass.newInstance();
        }

        this.processor = null;
        this.objects = new ArrayList<T>();
        this.runs = new ArrayList<List<T>>();
        this.temporaryFiles = new ArrayList<File>();
        this.lessThanCompare = order.lessThan();

        this.filesWritten = parameters.getCounter("Sorter Files Written");
        this.sorterCombineSteps = parameters.getCounter("Sorter Combine Steps");

        requestMemoryWarnings();
    }

    public void requestMemoryWarnings() {
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        long maxPoolSize = 0;
        MemoryPoolMXBean biggestPool = null;

        for (MemoryPoolMXBean pool : pools) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage usage = pool.getUsage();

            if (pool.isUsageThresholdSupported() &&
                    usage.getMax() > maxPoolSize) {
                maxPoolSize = usage.getMax();
                biggestPool = pool;
            }
        }

        if (biggestPool != null) {
            biggestPool.setUsageThreshold((long) (maxPoolSize * 0.7));
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            NotificationEmitter emitter = (NotificationEmitter) memoryBean;
            emitter.addNotificationListener(this, null, null);
        } else {
            throw new RuntimeException("Memory monitoring is not supported.");
        }
    }

    public void removeMemoryWarnings() {
        try {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            NotificationEmitter emitter = (NotificationEmitter) memoryBean;
            emitter.removeNotificationListener(this);
        } catch (ListenerNotFoundException e) {
            // do nothing
        }
    }

    public void handleNotification(Notification notification, Object handback) {
        if (notification.getType().equals(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED)) {
            flushRequested = true;
            final Sorter f = this;

            Thread t = new Thread() {
                @Override
                public void run() {
                    try {
                        f.flush();
                    } catch (IOException e) {
                        logger.severe(e.toString());
                    }
                }
            };

            t.start();
        }
    }

    public static void verify(TupleFlowParameters fullParameters, ErrorHandler handler) {
        Parameters parameters = fullParameters.getXML();
        String[] requiredParameters = {"order", "class"};

        if (!Verification.requireParameters(requiredParameters, parameters, handler)) {
            return;
        }
        String className = parameters.get("class");
        String[] orderSpec = parameters.get("order").split(" ");

        Verification.requireClass(className, handler);
        Verification.requireOrder(className, orderSpec, handler);

        if (parameters.containsKey("reducer")) {
            String reducerType = parameters.get("reducer");
            Verification.requireClass(reducerType, handler);
        }
    }

    public static String getInputClass(TupleFlowParameters parameters) {
        return parameters.getXML().get("class", "");
    }

    public static String getOutputClass(TupleFlowParameters parameters) {
        return parameters.getXML().get("class", "");
    }

    public static String[] getOutputOrder(TupleFlowParameters parameters) {
        String[] orderSpec = parameters.getXML().get("order", "").split(" ");
        return orderSpec;
    }

    @Override
    public String toString() {
        return order.getOrderedClass().getName() + " " + Arrays.asList(order.getOrderSpec());
    }

    public boolean needsFlush() {
        if (flushRequested) {
            return true;
        }
        return objects.size() > reduceInterval ||
                objects.size() + runsCount > limit;
    }

    public synchronized void process(T object) throws IOException {
        objects.add(object);
        flushIfNecessary();
    }

    public synchronized void flushIfNecessary() throws IOException {
        if (needsFlush()) {
            reduce();

            if (needsFlush()) {
                flush();
            }
        }
    }

    @Override
    /**
     * <p>Finishes sorting, then sends sorted output to later stages.</p>
     * 
     * <p>This method is intentionally unsynchronized, as synchronizing it 
     * tends to cause deadlock problems (since this method calls process on
     * later stages).</p>
     */
    public synchronized void close() throws IOException {
        // remove this object as quickly as possible from the alert queue
        // since we don't need to flush anymore
        removeMemoryWarnings();

        if (temporaryFiles.size() > 0) {
            combine();
        } else {
            reduce();
            combineRuns(processor);
        }
        processor.close();
    }

    /**
     * Reduces the number of buffered objects.  The recently buffered
     * objects are processed by a Reducer, if one was specified in the
     * constructor.  The resulting objects from this reduction are then
     * copied into a "reduced" set of buffered objects.  The process
     * resembles a generational garbage collector.
     * 
     * Even if no reducer exists, this sorts all the current objects and
     * sets them aside.  We perform this initial sort while the objects 
     * are still warm in the cache to get improved throughput overall.
     * 
     * Another benefit to reducing is the speed that we can respond to 
     * low memory events.  If a low memory event happens and the objects 
     * aren't sorted, we have to sort them first before writing them to disk.
     * The sorting process can take extra memory and time, which makes us
     * risk running out of RAM while trying to get data out onto the disk.
     */
    private synchronized void reduce() throws IOException {
        if (size() == 0) {
            return;
        }
        List<T> results = objects;

        if (reducer != null) {
            results = reducer.reduce(objects);
        }
        Collections.sort(results, lessThanCompare);
        runs.add(results);
        runsCount += results.size();

        objects = new ArrayList<T>();
    }

    /**
     * Returns the number of currently buffered objects.
     */
    private long size() {
        return runsCount + objects.size();
    }

    public synchronized void flush() throws IOException {
        if (size() == 0) {
            return;
        }
        reduce();
        assert objects.size() == 0;

        FileOrderedWriter<T> writer = getTemporaryWriter();
        combineRuns(writer);
        writer.close();
        if (filesWritten != null) filesWritten.increment();

        flushRequested = false;
    }

    private class RunWrapper<T> implements Comparable<RunWrapper<T>> {
        public Iterator<T> iterator;
        public T top;
        Comparator<T> lessThan;

        public RunWrapper(List<T> list, Comparator<T> lessThan) {
            iterator = list.iterator();
            this.lessThan = lessThan;
        }

        public int compareTo(RunWrapper<T> other) {
            T one = top;
            T two = other.top;

            int result = lessThan.compare(one, two);
            return result;
        }

        public boolean next() {
            if (iterator.hasNext()) {
                top = iterator.next();
                return true;
            } else {
                top = null;
                return false;
            }
        }
    }

    /**
     * Takes the sorted runs in the runs array, and combines them into a
     * single sorted list, which is processed by the processor called output.
     */
    private synchronized void combineRuns(Processor<T> output) throws IOException {
        PriorityQueue<RunWrapper<T>> queue = new PriorityQueue<RunWrapper<T>>();

        // make a run wrapper for each run we've got buffered, 
        // put it in the priority queue
        for (List<T> run : runs) {
            RunWrapper<T> wrapper = new RunWrapper<T>(run, lessThanCompare);
            if (wrapper.next()) {
                queue.offer(wrapper);
            }
        }

        // we expect that some runs will have lots of contiguous tuples,
        // in the case where the input is already almost sorted.  This loop
        // is optimized for that case.

        while (queue.size() > 1) {
            RunWrapper<T> wrapper = queue.poll();
            RunWrapper<T> next = queue.peek();

            output.process(wrapper.top);
            wrapper.next();

            while (wrapper.top != null &&
                    lessThanCompare.compare(wrapper.top, next.top) <= 0) {
                output.process(wrapper.top);
                wrapper.next();
            }

            if (wrapper.top != null) {
                queue.offer(wrapper);
            }
        }

        // process all objects from the final run
        if (queue.size() == 1) {
            RunWrapper<T> wrapper = queue.poll();

            do {
                output.process(wrapper.top);
            } while (wrapper.next());
        }

        runs.clear();
        runsCount = 0;
    }

    private synchronized FileOrderedWriter<T> getTemporaryWriter(long fileSize) throws IOException, FileNotFoundException {
        File temporary = Utility.createTemporary(fileSize * 4);
        FileOrderedWriter<T> writer = new FileOrderedWriter<T>(temporary, order);
        temporaryFiles.add(temporary);
        return writer;
    }

    private synchronized FileOrderedWriter<T> getTemporaryWriter() throws IOException, FileNotFoundException {
        File temporary = Utility.createTemporary();
        FileOrderedWriter<T> writer = new FileOrderedWriter<T>(temporary, order);
        temporaryFiles.add(temporary);
        return writer;
    }

    private synchronized void combine() throws IOException {
        flush();

        if (temporaryFiles.size() == 0) {
            return;
        }
        while (temporaryFiles.size() > fileLimit) {
            // sort all the files so that small ones come first, since those
            // are the ones we want to combine together.
            Collections.sort(temporaryFiles, new Comparator<File>() {
                         public int compare(File one, File two) {
                             long oneLength = one.length();
                             long twoLength = two.length();

                             if (oneLength > twoLength) {
                                 return 1;
                             } else if (oneLength < twoLength) {
                                 return -1;
                             }
                             return 0;
                         }
                     });

            // pick a set of files to merge and remove them from the regular file set
            ArrayList<File> temporaryFileSet = new ArrayList<File>(temporaryFiles.subList(0,
                                                                                          fileLimit));
            temporaryFiles.subList(0, fileLimit).clear();

            // calculate the total amount of space we'll need for this
            long totalFileSize = 0;

            for (File f : temporaryFiles) {
                totalFileSize += f.length();
            }

            // get a temporary writer that's big enough to handle all this data.
            // this adds the File to the end of the list of temporary files
            long oneGigabyte = 1024 * 1024 * 1024;
            FileOrderedWriter<T> writer = getTemporaryWriter(totalFileSize * 2 + oneGigabyte);

            // do the actual combination work
            combineStep(temporaryFileSet, writer);

            writer.close();
            temporaryFileSet.clear();
        }

        combineStep(temporaryFiles, processor);
        temporaryFiles.clear();
    }

    private synchronized void combineStep(List<File> files, Processor<T> output) throws FileNotFoundException, IOException {
        if (sorterCombineSteps != null) sorterCombineSteps.increment();

        ArrayList<String> filenames = new ArrayList<String>();

        for (File f : files) {
            filenames.add(f.getPath());
        }
        OrderedCombiner combiner = OrderedCombiner.combineFromFiles(filenames, order, output, false,
                                                                    combineBufferSize);
        combiner.run();

        for (File file : files) {
            file.delete();
        }
    }
}
