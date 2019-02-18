package org.jaudiotagger.audio.mp4;

import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.MP4Util.Movie;
import org.jcodec.containers.mp4.boxes.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;


/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * <p>
 * Self contained movie creator
 *
 * @author The JCodec project
 */
public class Flattern {


    public List<ProgressListener> listeners;

    public Flattern() {
        this.listeners = new ArrayList<ProgressListener>();
    }

    public interface ProgressListener {
        void trigger(int progress);
    }

    public void addProgressListener(ProgressListener listener) {
        this.listeners.add(listener);
    }

    public void flatternChannel(Movie movie, FileChannel out) throws IOException {
        MovieBox moov = movie.getMoov();

        if (!moov.isPureRefMovie())
            throw new IllegalArgumentException("movie should be reference");
        out.position(0);
        MP4Util.writeFullMovie(out, movie);

        int extraSpace = calcSpaceReq(moov);
        ByteBuffer buf = ByteBuffer.allocate(extraSpace);
        out.write(buf);

        long mdatOff = out.position();
        writeHeader(Header.createHeader("mdat", 0x100000001L), out);

        FileChannel[][] inputs = getInputs(moov);

        TrakBox[] tracks = moov.getTracks();
        ChunkReader[] readers = new ChunkReader[tracks.length];
        ChunkWriter[] writers = new ChunkWriter[tracks.length];
        Chunk[] head = new Chunk[tracks.length];
        int totalChunks = 0, writtenChunks = 0, lastProgress = 0;
        long[] off = new long[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            readers[i] = new ChunkReader(tracks[i]);
            totalChunks += readers[i].size();

            writers[i] = new ChunkWriter(tracks[i], inputs[i], out);
            head[i] = readers[i].next();
            if (tracks[i].isVideo())
                off[i] = 2 * moov.getTimescale();
        }

        while (true) {
            int min = -1;
            for (int i = 0; i < readers.length; i++) {
                if (head[i] == null)
                    continue;

                if (min == -1)
                    min = i;
                else {
                    long iTv = moov.rescale(head[i].getStartTv(), tracks[i].getTimescale()) + off[i];
                    long minTv = moov.rescale(head[min].getStartTv(), tracks[min].getTimescale()) + off[min];
                    if (iTv < minTv)
                        min = i;
                }
            }
            if (min == -1)
                break;
            writers[min].write(head[min]);
            head[min] = readers[min].next();
            writtenChunks++;

            lastProgress = calcProgress(totalChunks, writtenChunks, lastProgress);
        }

        for (int i = 0; i < tracks.length; i++) {
            writers[i].apply();
        }
        long mdatSize = out.position() - mdatOff;

        out.position(0);
        MP4Util.writeFullMovie(out, movie);

        long extra = mdatOff - out.position();
        if (extra < 0)
            throw new RuntimeException("Not enough space to write the header");
        writeHeader(Header.createHeader("free", extra), out);

        out.position(mdatOff);
        writeHeader(Header.createHeader("mdat", mdatSize), out);
    }

    private void writeHeader(Header header, FileChannel out) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(16);
        header.write(bb);
        bb.flip();
        out.write(bb);
    }

    private int calcProgress(int totalChunks, int writtenChunks, int lastProgress) {
        int curProgress = 100 * writtenChunks / totalChunks;
        if (lastProgress < curProgress) {
            lastProgress = curProgress;
            for (ProgressListener pl : this.listeners)
                pl.trigger(lastProgress);
        }
        return lastProgress;
    }

    protected FileChannel[][] getInputs(MovieBox movie) throws IOException {
        TrakBox[] tracks = movie.getTracks();
        FileChannel[][] result = new FileChannel[tracks.length][];
        for (int i = 0; i < tracks.length; i++) {
            DataRefBox drefs = NodeBox.findFirstPath(tracks[i], DataRefBox.class, Box.path("mdia.minf.dinf.dref"));
            if (drefs == null) {
                throw new RuntimeException("No data references");
            }
            List<Box> entries = drefs.getBoxes();
            FileChannel[] e = new FileChannel[entries.size()];
            FileChannel[] inputs = new FileChannel[entries.size()];
            for (int j = 0; j < e.length; j++) {
                inputs[j] = resolveDataRef(entries.get(j));
            }
            result[i] = inputs;
        }
        return result;
    }

    private int calcSpaceReq(MovieBox movie) {
        int sum = 0;
        TrakBox[] tracks = movie.getTracks();
        for (int i = 0; i < tracks.length; i++) {
            TrakBox trakBox = tracks[i];
            ChunkOffsetsBox stco = trakBox.getStco();
            if (stco != null)
                sum += stco.getChunkOffsets().length * 4;
        }
        return sum;
    }

    public FileChannel resolveDataRef(Box box) throws IOException {
        if (box instanceof UrlBox) {
            String url = ((UrlBox) box).getUrl();
            if (!url.startsWith("file://"))
                throw new RuntimeException("Only file:// urls are supported in data reference");
            return new FileInputStream(new File(url.substring(7))).getChannel();
        } else if (box instanceof AliasBox) {
            String uxPath = ((AliasBox) box).getUnixPath();
            if (uxPath == null)
                throw new RuntimeException("Could not resolve alias");
            return new FileInputStream(new File(uxPath)).getChannel();
        } else {
            throw new RuntimeException(box.getHeader().getFourcc() + " dataref type is not supported");
        }
    }

    public void flattern(Movie movie, File video) throws IOException {
        video.delete();
        try (FileChannel out = new FileOutputStream(video).getChannel()) {
            flatternChannel(movie, out);
        }
    }
}