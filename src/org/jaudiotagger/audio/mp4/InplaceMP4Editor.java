package org.jaudiotagger.audio.mp4;

import org.jaudiotagger.audio.generic.Utils;
import org.jaudiotagger.tag.datatype.Pair;
import org.jcodec.containers.mp4.BoxFactory;
import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.MP4Util.Atom;
import org.jcodec.containers.mp4.boxes.Box;
import org.jcodec.containers.mp4.boxes.Header;
import org.jcodec.containers.mp4.boxes.MovieBox;
import org.jcodec.containers.mp4.boxes.MovieFragmentBox;

import java.io.*;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Parses MP4 header and allows custom MP4Editor to modify it, then tries to put
 * the resulting header into the same place relatively to a file.
 * 
 * This might not work out, for example if the resulting header is bigger then
 * the original.
 * 
 * Use this class to make blazing fast changes to MP4 files when you know your
 * are not adding anything new to the header, perhaps only patching some values
 * or removing stuff from the header.
 * 
 * @author The JCodec project
 * 
 */
public class InplaceMP4Editor {

    /**
     * Tries to modify movie header in place according to what's implemented in
     * the edit, the file gets physically modified if the operation is
     * successful. No temporary file is created.
     * 
     * @param fi A file channel to be modified
     * @param edit
     *            An edit to be carried out on a movie header
     * @return Whether or not edit was successful, i.e. was there enough place
     *         to put the new header
     * @throws IOException
     * @throws Exception
     */
    public boolean modify(FileChannel fi, MovieBox edit) throws IOException {
        List<Pair<Atom, ByteBuffer>> fragments = doTheFix(fi, edit);
        if (fragments == null)
            return false;

        // If everything is clean, only then actually writing stuff to the
        // file
        for (Pair<Atom, ByteBuffer> fragment : fragments) {
            replaceBox(fi, fragment.getKey(), fragment.getValue());
        }
        return true;
    }

    /**
     * Tries to modify movie header in place according to what's implemented in
     * the edit. Copies modified contents to a new file.
     * 
     * Note: The header is still edited in-place, so the new file will have
     * all-the-same sample offsets.
     * 
     * Note: Still subject to the same limitations as 'modify', i.e. the new
     * header must 'fit' into an old place.
     * 
     * This method is useful when you can't write to the original file, for ex.
     * you don't have permission.
     * 
     * @param src
     *            An original file
     * @param dst
     *            A file to store the modified copy
     * @param edit
     *            An edit logic to apply
     * @return
     * @throws IOException
     */
    public boolean copy(File src, File dst, MovieBox edit) throws IOException {
        try (FileChannel fi = new FileInputStream(src).getChannel();
             FileChannel fo = new FileOutputStream(dst).getChannel()) {

            List<Pair<Atom, ByteBuffer>> fragments = doTheFix(fi, edit);
            if (fragments == null)
                return false;

            Map<Long, ByteBuffer> rewrite = fragments
                    .stream()
                    .collect(Collectors.toMap(p -> p.getKey().getOffset(), Pair::getValue));

            // If everything is clean, only then actually start writing file
            for (Atom atom : MP4Util.getRootAtoms(fi)) {
                ByteBuffer byteBuffer = rewrite.get(atom.getOffset());
                if (byteBuffer != null)
                    fo.write(byteBuffer);
                else
                    atom.copy(fi, fo);
            }

            return true;
        }
    }

    /**
     * Tries to modify movie header in place according to what's implemented in
     * the edit. Copies modified contents to a new file with the same name
     * erasing the original file if successful.
     * 
     * This is a shortcut for 'copy' when you want the new file to have the same
     * name but for some reason can not modify the original file in place. Maybe
     * modifications of files are expensive or not supported on your filesystem.
     * 
     * @param src
     *            A source and destination file
     * @param edit
     *            An edit to be applied
     * @return
     * @throws IOException
     */
    public boolean replace(File src, MovieBox edit) throws IOException {
        File tmp = new File(src.getParentFile(), "." + src.getName());
        if (copy(src, tmp, edit)) {
            tmp.renameTo(src);
            return true;
        }
        return false;
    }

    private List<Pair<Atom, ByteBuffer>> doTheFix(FileChannel fi, MovieBox edit) throws IOException {
        Atom moovAtom = getMoov(fi);

        ByteBuffer moovBuffer = fetchBox(fi, moovAtom);
        MovieBox moovBox = (MovieBox) parseBox(moovBuffer);

        List<Pair<Atom, ByteBuffer>> fragments = new LinkedList<>();
        if (Box.containsBox(moovBox, "mvex")) {
            List<Pair<ByteBuffer, MovieFragmentBox>> temp = new LinkedList<>();
            for (Atom fragAtom : getFragments(fi)) {
                ByteBuffer fragBuffer = fetchBox(fi, fragAtom);
                fragments.add(new Pair<>(fragAtom, fragBuffer));
                MovieFragmentBox fragBox = (MovieFragmentBox) parseBox(fragBuffer);
                fragBox.setMovie(moovBox);
                temp.add(new Pair<>(fragBuffer, fragBox));
            }

            for (Pair<ByteBuffer, ? extends Box> frag : temp) {
                if (!rewriteBox(frag.getKey(), frag.getValue()))
                    return null;
            }
        } else {
            for (Box box: edit.getBoxes()) {
                moovBox.replaceBox(box);
            }
        }

        if (!rewriteBox(moovBuffer, moovBox))
            return null;
        fragments.add(new Pair<>(moovAtom, moovBuffer));
        return fragments;
    }

    private void replaceBox(FileChannel fi, Atom atom, ByteBuffer buffer) throws IOException {
        fi.position(atom.getOffset());
        fi.write(buffer);
    }

    private boolean rewriteBox(ByteBuffer buffer, Box box) {
        try {
            buffer.clear();
            box.write(buffer);
            if (buffer.hasRemaining()) {
                if (buffer.remaining() < 8)
                    return false;
                buffer.putInt(buffer.remaining());
                buffer.put(new byte[] { 'f', 'r', 'e', 'e' });
            }
            buffer.flip();
            return true;
        } catch (BufferOverflowException e) {
            return false;
        }
    }

    private ByteBuffer fetchBox(FileChannel fi, Atom moov) throws IOException {
        fi.position(moov.getOffset());
        ByteBuffer oldMov = Utils.fetchFromChannel(fi, (int) moov.getHeader().getSize());
        return oldMov;
    }

    private Box parseBox(ByteBuffer oldMov) {
        Header header = Header.read(oldMov);
        Box box = Box.parseBox(oldMov, header, BoxFactory.getDefault());
        return box;
    }

    private Atom getMoov(FileChannel f) throws IOException {
        for (Atom atom : MP4Util.getRootAtoms(f)) {
            if ("moov".equals(atom.getHeader().getFourcc())) {
                return atom;
            }
        }
        return null;
    }

    private List<Atom> getFragments(FileChannel f) throws IOException {
        List<Atom> result = new LinkedList<Atom>();
        for (Atom atom : MP4Util.getRootAtoms(f)) {
            if ("moof".equals(atom.getHeader().getFourcc())) {
                result.add(atom);
            }
        }
        return result;
    }
}