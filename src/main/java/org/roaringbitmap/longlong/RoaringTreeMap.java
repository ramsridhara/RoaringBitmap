package org.roaringbitmap.longlong;

import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

import org.roaringbitmap.BitmapDataProvider;
import org.roaringbitmap.IntConsumer;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

// this class is not thread-safe
// @Beta
public class RoaringTreeMap implements Externalizable, ImmutableLongBitmapDataProvider {

  // Not final to enable initialization in Externalizable.readObject
  protected NavigableMap<Integer, BitmapDataProvider> highToBitmap;

  // If true, we handle longs a plain java longs: -1 if right before 0
  // If false, we handle longs as unsigned longs: 0 has no predecessor and Long.MAX_VALUE + 1L is
  // expressed as a
  // negative long
  private boolean signedLongs = false;

  // Prevent recomputing all cardinalities when requesting consecutive ranks
  private transient int firstHighNotValid = Integer.MIN_VALUE;

  // This boolean needs firstHighNotValid == Integer.MAX_VALUE to be allowed to be true
  // If false, it means nearly all cumulated cardinalities are valid, except high=Integer.MAX_VALUE
  // If true, it means all cumulated cardinalities are valid, even high=Integer.MAX_VALUE
  private transient boolean allValid = false;

  // TODO: I would prefer not managing arrays myself
  private transient long[] sortedCumulatedCardinality = new long[0];
  private transient int[] sortedHighs = new int[0];

  // Enable random-access to any bitmap, without requiring a new Iterator instance
  private transient final List<BitmapDataProvider> lowBitmaps = new ArrayList<>();

  // We guess consecutive .addLong will be on proximate longs: we remember the bitmap attached to
  // this bucket in order
  // to skip the indirection
  private transient Map.Entry<Integer, BitmapDataProvider> latestAddedHigh = null;

  /**
   * TMP By default, we consider longs are signed longs: normal longs: 0 is preceded by -1 and
   * Long.MAX_VALUE has no successor
   */
  // TODO: Should be 'By default, we consider longs are unsigned 64bits longlong' to comply with
  // RoaringBitmap
  public RoaringTreeMap() {
    this(true);
  }

  /**
   * 
   * @param signedLongs should long be ordered as plain java longs (signedLongs == true) or as
   *        unsigned 64bits long (signedLongs == false)
   */
  public RoaringTreeMap(boolean signedLongs) {
    this.signedLongs = signedLongs;

    if (signedLongs) {
      highToBitmap = new TreeMap<>();
    } else {
      highToBitmap = new TreeMap<>(RoaringIntPacking.unsignedComparator());
    }

    resetPerfHelpers();
  }

  private void resetPerfHelpers() {
    if (signedLongs) {
      firstHighNotValid = Integer.MIN_VALUE;
    } else {
      firstHighNotValid = 0;
    }
    allValid = false;

    sortedCumulatedCardinality = new long[0];
    sortedHighs = new int[0];

    // In case of de-serialization, we need to add back all bitmaps
    lowBitmaps.clear();
    // lowBitmaps.addAll(highToBitmap.values());

    latestAddedHigh = null;
  }

  /**
   * Add the value to the container (set the value to "true"), whether it already appears or not.
   *
   * Java lacks native unsigned integers but the x argument is considered to be unsigned. Within
   * bitmaps, numbers are ordered according to {@link Integer#compareUnsigned}. We order the numbers
   * like 0, 1, ..., 2147483647, -2147483648, -2147483647,..., -1.
   *
   * @param x integer value
   */
  public void addLong(long x) {
    int high = high(x);
    int low = low(x);

    // Copy the reference to prevent race-condition
    Map.Entry<Integer, BitmapDataProvider> local = latestAddedHigh;

    BitmapDataProvider bitmap;
    if (local != null && local.getKey().intValue() == high) {
      bitmap = local.getValue();
    } else {
      bitmap = highToBitmap.get(high);
      if (bitmap == null) {
        bitmap = new MutableRoaringBitmap();
        pushBitmapForHigh(high, bitmap);
      }
      latestAddedHigh = new AbstractMap.SimpleImmutableEntry<>(high, bitmap);
    }
    bitmap.add(low);

    // The cardinalities after this bucket may not be valid anymore
    if (compare(firstHighNotValid, high) > 0) {
      firstHighNotValid = high;

      int indexNotValid = binarySearch(sortedHighs, firstHighNotValid);

      final int indexAfterWhichToReset;
      if (indexNotValid >= 0) {
        indexAfterWhichToReset = indexNotValid;
      } else {
        // We have added a value for a branch new high
        indexAfterWhichToReset = -indexNotValid - 1;
      }

      // This way, sortedHighs remains sorted
      Arrays.fill(sortedHighs, indexAfterWhichToReset, sortedHighs.length, highestHigh());
    }
    allValid = false;
  }

  private int compare(int x, int y) {
    if (signedLongs) {
      return Integer.compare(x, y);
    } else {
      return RoaringIntPacking.compareUnsigned(x, y);
    }
  }

  private void pushBitmapForHigh(int high, BitmapDataProvider bitmap) {
    // TODO .size is too slow
    // int nbHighBefore = highToBitmap.headMap(high).size();

    BitmapDataProvider previous = highToBitmap.put(high, bitmap);
    if (previous != null) {
      throw new IllegalStateException("Should push only not-existing high");
    }

    // If there is 1 bucket before, we need to add at index 1
    // TODO .size were too slow
    // lowBitmaps.add(nbHighBefore, bitmap);
  }

  private int low(long id) {
    return RoaringIntPacking.low(id);
  }

  private int high(long id) {
    return RoaringIntPacking.high(id);
  }

  /**
   * Returns the number of distinct integers added to the bitmap (e.g., number of bits set).
   *
   * @return the cardinality
   */
  public long getLongCardinality() {
    if (highToBitmap.isEmpty()) {
      return 0L;
    }

    ensureCumulatives(highestHigh());

    return sortedCumulatedCardinality[sortedCumulatedCardinality.length - 1];
  }

  /**
   * Return the jth value stored in this bitmap.
   *
   * @param j index of the value
   *
   * @return the value
   */
  public long select(final long j) {
    // Ensure all cumulatives as we we have straightforward way to know inadvance the high of the
    // j-th value
    ensureCumulatives(highestHigh());

    // Search the whole array as ensureCumulatives has been fully computed
    // Use normal binarySearch as cardinality does not depends on considering longs signed or
    // unsigned
    int position =
        Arrays.binarySearch(sortedCumulatedCardinality, 0, sortedCumulatedCardinality.length, j);

    if (position >= 0) {
      if (position == sortedCumulatedCardinality.length - 1) {
        // .select has been called on this.getCardinality
        return throwSelectInvalidIndex(j);
      }

      // There is a bucket leading to this cardinality: the j-th element is the first element of
      // next bucket
      BitmapDataProvider nextBitmap = lowBitmaps.get(position + 1);
      return RoaringIntPacking.pack(sortedHighs[position + 1], nextBitmap.select(0));
    } else {
      // There is no bucket with this cardinality
      int insertionPoint = -position - 1;

      final long previousBucketCardinality;
      if (insertionPoint == 0) {
        previousBucketCardinality = 0L;
      } else {
        previousBucketCardinality = sortedCumulatedCardinality[insertionPoint - 1];
      }

      // We get a 'select' query for a single bitmap: should fit in an int
      final int givenBitmapSelect = (int) (j - previousBucketCardinality);

      BitmapDataProvider bitmaps = lowBitmaps.get(insertionPoint);

      int low = bitmaps.select(givenBitmapSelect);

      int high = sortedHighs[insertionPoint];

      return RoaringIntPacking.pack(high, low);
    }
  }

  private long throwSelectInvalidIndex(long j) {
    // see org.roaringbitmap.buffer.ImmutableRoaringBitmap.select(int)
    throw new IllegalArgumentException(
        "select " + j + " when the cardinality is " + this.getLongCardinality());
  }

  /**
   * For better performance, consider the Use the {@link #forEach forEach} method.
   *
   * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
   */
  public Iterator<Long> iterator() {
    final LongIterator it = getLongIterator();

    return new Iterator<Long>() {

      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public Long next() {
        return it.next();
      }

      @Override
      public void remove() {
        // TODO?
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  public void forEach(final LongConsumer lc) {
    for (final Entry<Integer, BitmapDataProvider> highEntry : highToBitmap.entrySet()) {
      highEntry.getValue().forEach(new IntConsumer() {

        @Override
        public void accept(int low) {
          lc.accept(RoaringIntPacking.pack(highEntry.getKey(), low));
        }
      });
    }
  }

  @Override
  public long rankLong(long id) {
    int high = RoaringIntPacking.high(id);
    int low = RoaringIntPacking.low(id);

    int indexOk = ensureCumulatives(high);

    // the position is maxes by high, and the values at the end of sortedHighs may be compromised
    // (depending on firstHighNotValid)
    // TODO: binarySearch is NOT ok if unsigned
    int bitmapPosition = binarySearch(sortedHighs, 0, indexOk, high);

    if (bitmapPosition >= 0) {
      // There is a bucket holding this item

      final long previousBucketCardinality;
      if (bitmapPosition == 0) {
        previousBucketCardinality = 0;
      } else {
        previousBucketCardinality = sortedCumulatedCardinality[bitmapPosition - 1];
      }

      BitmapDataProvider lowBitmap = lowBitmaps.get(bitmapPosition);

      // Rank is previous cardinality plus rank in current bitmap
      return previousBucketCardinality + lowBitmap.rankLong(low);
    } else {
      // There is no bucket holding this item: insertionPoint is previous bitmap
      int insertionPoint = -bitmapPosition - 1;

      if (insertionPoint == 0) {
        // this key is before all inserted keys
        return 0;
      } else {
        // The rank is the cardinality of this previous bitmap
        return sortedCumulatedCardinality[insertionPoint - 1];
      }
    }
  }

  /**
   * 
   * @param high for which high bucket should we compute the cardinality
   * @return the highest validatedIndex
   */
  protected int ensureCumulatives(int high) {
    if (allValid) {
      return sortedHighs.length;
    } else if (compare(high, firstHighNotValid) < 0) {
      // The high cardinality is strictly below the first not valid: it is valid

      // TODO: this is invalid: sortedHighs may have only a subset of valid values
      int position = binarySearch(sortedHighs, high);

      if (position >= 0) {
        // This high has a bitmap: +1 as this index will be used as right (excluded) bound in a
        // binary-search
        return position + 1;
      } else {
        // This high has no bitmap: it could be between 2 highs with bitmaps
        int insertionPosition = -position - 1;
        return insertionPosition;
      }
    } else {
      // For each deprecated buckets
      SortedMap<Integer, BitmapDataProvider> tailMap =
          highToBitmap.tailMap(firstHighNotValid, true);

      int indexOk = highToBitmap.size() - tailMap.size();
      for (Map.Entry<Integer, BitmapDataProvider> e : tailMap.entrySet()) {

        int currentHigh = e.getKey();

        if (compare(currentHigh, high) > 0) {
          // No need to compute more than needed
          break;
        }

        ensureOne(e, currentHigh, indexOk);

        // We have added one valid cardinality
        indexOk++;
      }

      if (highToBitmap.isEmpty() || high == highToBitmap.lastKey().intValue()) {
        // We have compute all cardinalities
        allValid = true;
      }

      return indexOk;
    }
  }

  private int binarySearch(int[] array, int key) {
    if (signedLongs) {
      return Arrays.binarySearch(array, key);
    } else {
      return unsignedBinarySearch(array, 0, array.length, key,
          RoaringIntPacking.unsignedComparator());
    }
  }

  private int binarySearch(int[] array, int from, int to, int key) {
    if (signedLongs) {
      return Arrays.binarySearch(array, from, to, key);
    } else {
      return unsignedBinarySearch(array, from, to, key, RoaringIntPacking.unsignedComparator());
    }
  }

  // From Arrays.binarySearch (Comparator). Check with ROaring Util.unsignedBinarySearch
  private static int unsignedBinarySearch(int[] a, int fromIndex, int toIndex, int key,
      Comparator<? super Integer> c) {
    int low = fromIndex;
    int high = toIndex - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      int midVal = a[mid];
      int cmp = c.compare(midVal, key);
      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }
    return -(low + 1); // key not found.
  }

  private void ensureOne(Map.Entry<Integer, BitmapDataProvider> e, int currentHigh, int indexOk) {

    // sortedHighs are valid only up to some index
    assert indexOk <= sortedHighs.length;
    int index = binarySearch(sortedHighs, 0, indexOk, currentHigh);

    if (index >= 0) {
      // This bitmap has already been registered
      BitmapDataProvider bitmap = e.getValue();
      assert bitmap == highToBitmap.get(sortedHighs[index]);

      final long previousCardinality;
      if (currentHigh >= 1) {
        previousCardinality = sortedCumulatedCardinality[index - 1];
      } else {
        previousCardinality = 0;
      }
      sortedCumulatedCardinality[index] = previousCardinality + bitmap.getCardinality();

      if (currentHigh == Integer.MAX_VALUE) {
        allValid = true;
        firstHighNotValid = currentHigh;
      } else {
        firstHighNotValid = currentHigh + 1;
      }
    } else {
      int insertionPosition = -index - 1;

      // This is a new key
      if (insertionPosition >= sortedHighs.length) {
        // Insertion at the end
        sortedHighs = Arrays.copyOf(sortedHighs, sortedHighs.length + 1);
        sortedCumulatedCardinality =
            Arrays.copyOf(sortedCumulatedCardinality, sortedCumulatedCardinality.length + 1);
      } else {
        // Insertion in the middle
        int previousLength = sortedHighs.length;
        sortedHighs = Arrays.copyOf(sortedHighs, previousLength + 1);
        // Ensure the new 0 is in the middle
        System.arraycopy(sortedHighs, insertionPosition, sortedHighs, insertionPosition + 1,
            previousLength - insertionPosition);

        sortedCumulatedCardinality =
            Arrays.copyOf(sortedCumulatedCardinality, sortedCumulatedCardinality.length + 1);

        // No need to copy higher cardinalities as anyway, the cardinalities may not be valid
        // anymore
      }
      sortedHighs[insertionPosition] = currentHigh;
      lowBitmaps.add(insertionPosition, e.getValue());

      final long previousCardinality;
      if (insertionPosition >= 1) {
        previousCardinality = sortedCumulatedCardinality[insertionPosition - 1];
      } else {
        previousCardinality = 0;
      }

      sortedCumulatedCardinality[insertionPosition] =
          previousCardinality + e.getValue().getLongCardinality();

      if (currentHigh == highestHigh()) {
        // We are already on the highest high. Do not set allValid as it is set anyway out of the
        // loop
        firstHighNotValid = currentHigh;
      } else {
        // The first not valid is the next high
        firstHighNotValid = currentHigh + 1;
      }
    }
  }

  private int highestHigh() {
    if (signedLongs) {
      return Integer.MAX_VALUE;
    } else {
      return -1;
    }
  }

  /**
   * In-place bitwise OR (union) operation. The current bitmap is modified.
   *
   * @param x2 other bitmap
   */
  public void or(final RoaringTreeMap x2) {
    boolean firstBucket = true;

    for (Entry<Integer, BitmapDataProvider> e : x2.highToBitmap.entrySet()) {
      // Keep object to prevent auto-boxing
      Integer high = e.getKey();

      BitmapDataProvider currentBitmap = this.highToBitmap.get(high);

      BitmapDataProvider lowBitmap2 = e.getValue();
      // TODO Reviewers: is it a good idea to rely on BitmapDataProvider except in methods
      // expecting an actual MutableRoaringBitmap?
      if ((currentBitmap == null || currentBitmap instanceof MutableRoaringBitmap)
          && lowBitmap2 instanceof MutableRoaringBitmap) {

        if (currentBitmap == null) {
          // Clone to prevent future modification of this modifying the input Bitmap
          BitmapDataProvider lowBitmap2Clone = ((MutableRoaringBitmap) lowBitmap2).clone();


          pushBitmapForHigh(high, lowBitmap2Clone);
        } else {
          ((MutableRoaringBitmap) currentBitmap).or((MutableRoaringBitmap) lowBitmap2);
        }
      } else {
        throw new UnsupportedOperationException(
            ".or is not between " + this.getClass() + " and " + lowBitmap2.getClass());
      }

      if (firstBucket) {
        firstBucket = false;

        firstHighNotValid = Math.min(firstHighNotValid, high);
        allValid = false;
      }
    }

  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    out.writeBoolean(signedLongs);
    out.writeObject(highToBitmap);
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    signedLongs = in.readBoolean();
    highToBitmap = (NavigableMap<Integer, BitmapDataProvider>) in.readObject();

    resetPerfHelpers();
  }

  /**
   * A string describing the bitmap.
   *
   * @return the string
   */
  @Override
  public String toString() {
    final StringBuilder answer = new StringBuilder();
    final LongIterator i = this.getLongIterator();
    answer.append("{");
    if (i.hasNext()) {
      if (signedLongs) {
        answer.append(i.next());
      } else {
        answer.append(RoaringIntPacking.toUnsignedString(i.next()));
      }
    }
    while (i.hasNext()) {
      answer.append(",");
      // to avoid using too much memory, we limit the size
      if (answer.length() > 0x80000) {
        answer.append("...");
        break;
      }
      if (signedLongs) {
        answer.append(i.next());
      } else {
        answer.append(RoaringIntPacking.toUnsignedString(i.next()));
      }

    }
    answer.append("}");
    return answer.toString();
  }


  /**
   *
   * For better performance, consider the Use the {@link #forEach forEach} method.
   *
   * @return a custom iterator over set bits, the bits are traversed in ascending sorted order
   */
  public LongIterator getLongIterator() {
    final Iterator<Map.Entry<Integer, BitmapDataProvider>> it = highToBitmap.entrySet().iterator();

    return new LongIterator() {

      protected int currentKey;
      protected IntIterator currentIt;

      @Override
      public boolean hasNext() {
        if (currentIt == null) {
          // Were initially empty
          if (!moveToNextEntry(it)) {
            return false;
          }
        }

        while (true) {
          if (currentIt.hasNext()) {
            return true;
          } else {
            if (!moveToNextEntry(it)) {
              return false;
            }
          }
        }
      }

      /**
       *
       * @param it the underlying iterator which has to be moved to next long
       * @return true if we MAY have more entries. false if there is definitely nothing more
       */
      private boolean moveToNextEntry(Iterator<Map.Entry<Integer, BitmapDataProvider>> it) {
        if (it.hasNext()) {
          Map.Entry<Integer, BitmapDataProvider> next = it.next();
          currentKey = next.getKey();
          currentIt = next.getValue().getIntIterator();

          // We may have more long
          return true;
        } else {
          // We know there is nothing more
          return false;
        }
      }

      @Override
      public long next() {
        if (hasNext()) {
          return RoaringIntPacking.pack(currentKey, currentIt.next());
        } else {
          throw new IllegalStateException("empty");
        }
      }

      @Override
      public LongIterator clone() {
        throw new UnsupportedOperationException("TODO");
      }
    };
  }

  @Override
  public boolean contains(long x) {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public PeekableLongIterator getIntIterator() {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public LongIterator getReverseIntIterator() {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public int getSizeInBytes() {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public long getLongSizeInBytes() {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public boolean isEmpty() {
    return getLongCardinality() >= 1;
  }

  @Override
  public ImmutableLongBitmapDataProvider limit(long x) {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public void serialize(DataOutput out) throws IOException {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public int serializedSizeInBytes() {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public long[] toArray() {
    throw new UnsupportedOperationException("TODO");
  }

  /**
   * Generate a bitmap with the specified values set to true. The provided longs values don't have
   * to be in sorted order, but it may be preferable to sort them from a performance point of view.
   *
   * @param dat set values
   * @return a new bitmap
   */
  public static RoaringTreeMap bitmapOf(final long... dat) {
    final RoaringTreeMap ans = new RoaringTreeMap();
    ans.add(dat);
    return ans;
  }

  /**
   * Set all the specified values to true. This can be expected to be slightly faster than calling
   * "add" repeatedly. The provided integers values don't have to be in sorted order, but it may be
   * preferable to sort them from a performance point of view.
   *
   * @param dat set values
   */
  public void add(long... dat) {
    for (long oneLong : dat) {
      addLong(oneLong);
    }
  }

  public LongIterator getReverseLongIterator() {
    // TODO Auto-generated method stub
    return null;
  }
}
