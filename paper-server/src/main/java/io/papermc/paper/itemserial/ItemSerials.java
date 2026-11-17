package io.papermc.paper.itemserial;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.LongStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Серийные номера единиц предметов в стаке.
 * <p>
 * Номера стака хранятся в {@link ItemStack#serials} как пары {@code (start, len)}: единицы с номерами
 * {@code start .. start + len - 1}. {@code start == -1} означает {@code len} единиц без номеров.
 * Массив нормализован (соседние куски склеены), неизменяем и сумма {@code len} всегда равна количеству в стаке.
 * {@code null} означает, что у стака нет ни одного номера.
 * <p>
 * Номера не входят в компоненты предмета: клиент их не видит, стакание не меняется. На диск они пишутся
 * полем {@code item_serials} в {@link ItemStack#MAP_CODEC}.
 * <p>
 * Когда копия стака и оригинал уменьшаются в одном тике (split, copy + setCount, слияние через
 * {@link ItemStack#growFrom}), они делят номера через общий {@link Claim}, чтобы одна единица не оказалась в двух стаках.
 */
public final class ItemSerials {

    /**
     * Включена ли система. Выставлять до загрузки миров: при {@code false} номера не читаются из сохранений
     * и не пишутся в них, а все операции со стаками работают как в ванилле.
     */
    public static boolean ENABLED = false;

    /** Маркер куска без номеров. */
    public static final long UNTRACKED = -1L;

    public static final Codec<long[]> CODEC = Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream);

    private ItemSerials() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Выдача номеров

    /** Сколько номеров резервируется в файле за раз. */
    private static final long RESERVE_BLOCK = 1_000_000L;
    private static final Path COUNTER_FILE = Path.of("item-serials.txt");
    private static long nextSerial = -1L;
    private static long reservedUntil = -1L;

    /**
     * Резервирует {@code amount} новых номеров подряд и возвращает первый.
     * Граница резерва записывается в {@code item-serials.txt} в папке сервера до того, как номера отданы,
     * поэтому после рестарта номера не повторяются (хвост недоиспользованного резерва просто пропадает).
     */
    public static synchronized long allocate(final long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (nextSerial < 0) {
            long saved = 1L;
            try {
                if (Files.exists(COUNTER_FILE)) {
                    saved = Long.parseLong(Files.readString(COUNTER_FILE, StandardCharsets.UTF_8).trim());
                }
            } catch (final IOException | NumberFormatException e) {
                throw new IllegalStateException("Cannot read " + COUNTER_FILE.toAbsolutePath(), e);
            }
            nextSerial = Math.max(1L, saved);
            reservedUntil = nextSerial;
        }
        if (nextSerial + amount > reservedUntil) {
            final long newReserved = nextSerial + Math.max(amount, RESERVE_BLOCK);
            try {
                final Path tmp = COUNTER_FILE.resolveSibling(COUNTER_FILE.getFileName() + ".tmp");
                Files.writeString(tmp, Long.toString(newReserved), StandardCharsets.UTF_8);
                Files.move(tmp, COUNTER_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                throw new IllegalStateException("Cannot write " + COUNTER_FILE.toAbsolutePath(), e);
            }
            reservedUntil = newReserved;
        }
        final long start = nextSerial;
        nextSerial += amount;
        return start;
    }

    /**
     * Выдаёт всем единицам стака новые номера подряд (старые номера стака отбрасываются).
     *
     * @return первый номер или {@code -1}, если система выключена или стак пуст
     */
    public static long mint(final ItemStack stack) {
        if (!ENABLED || stack.isEmpty()) {
            return -1L;
        }
        final long start = allocate(stack.getCount());
        assign(stack, start);
        return start;
    }

    public static long mint(final org.bukkit.inventory.@Nullable ItemStack stack) {
        return stack == null ? -1L : mint(CraftItemStack.unwrap(stack));
    }

    /**
     * Выдаёт новые номера только единицам стака без номеров (кускам {@code -1}); уже выданные номера не меняются.
     *
     * @return сколько единиц получили номера
     */
    public static int mintUntracked(final ItemStack stack) {
        if (!ENABLED || stack.isEmpty()) {
            return 0;
        }
        final int count = stack.getCount();
        final long[] serials = stack.serials;
        if (serials == null) {
            mint(stack);
            return count;
        }
        long untracked = 0;
        for (int i = 0; i < serials.length; i += 2) {
            if (serials[i] == UNTRACKED) {
                untracked += serials[i + 1];
            }
        }
        if (untracked == 0) {
            return 0;
        }
        long next = allocate(untracked);
        final long[] result = serials.clone();
        for (int i = 0; i < result.length; i += 2) {
            if (result[i] == UNTRACKED) {
                result[i] = next;
                next += result[i + 1];
            }
        }
        stack.serials = normalize(result);
        return (int) untracked;
    }

    public static int mintUntracked(final org.bukkit.inventory.@Nullable ItemStack stack) {
        return stack == null ? 0 : mintUntracked(CraftItemStack.unwrap(stack));
    }

    /**
     * Ставит {@code target} номера {@code source}: для предмета, пересобранного из другого (тот же предмет, новый объект).
     * Если {@code target} больше, лишние единицы остаются без номеров; если меньше, берутся первые.
     */
    public static void copyFrom(final ItemStack source, final ItemStack target) {
        if (!ENABLED || source == target || target.isEmpty() || source.isEmpty() || source.serials == null) {
            return;
        }
        detach(target);
        target.serials = slice(source.serials, source.getCount(), 0, target.getCount());
    }

    public static void copyFrom(final org.bukkit.inventory.@Nullable ItemStack source, final org.bukkit.inventory.@Nullable ItemStack target) {
        if (source != null && target != null) {
            copyFrom(CraftItemStack.unwrap(source), CraftItemStack.unwrap(target));
        }
    }

    /** Как {@link ItemStack#growFrom}: {@code target} растёт на {@code amount} единиц из {@code source}, {@code source} не меняется. */
    public static void growFrom(final org.bukkit.inventory.ItemStack target, final org.bukkit.inventory.ItemStack source, final int amount) {
        final ItemStack dst = CraftItemStack.unwrap(target);
        if (dst.isEmpty()) {
            target.setAmount(target.getAmount() + amount);
            return;
        }
        dst.growFrom(CraftItemStack.unwrap(source), amount);
    }

    /** Ставит стаку номера {@code start .. start + count - 1}. Номера должны быть получены через {@link #allocate}. */
    public static void assign(final ItemStack stack, final long start) {
        if (!ENABLED || stack.isEmpty()) {
            return;
        }
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0: " + start);
        }
        detach(stack);
        stack.serials = new long[]{start, stack.getCount()};
    }

    /** Убирает у стака все номера. */
    public static void clear(final ItemStack stack) {
        detach(stack);
        stack.serials = null;
    }

    /** Номера стака в формате пар {@code (start, len)}; {@code null}, если номеров нет. Возвращается копия. */
    public static long @Nullable [] get(final ItemStack stack) {
        return stack.serials == null ? null : stack.serials.clone();
    }

    public static long @Nullable [] get(final org.bukkit.inventory.@Nullable ItemStack stack) {
        return stack == null ? null : get(CraftItemStack.unwrap(stack));
    }

    /**
     * Переносит {@code amount} единиц из {@code source} в {@code target} вместе с номерами.
     * В отличие от ванильного {@code grow}, {@code source} тоже уменьшается.
     * Для слияний в коде плагинов вместо пары {@code target.setAmount(+n)} / {@code source.setAmount(-n)}.
     */
    public static void transfer(final org.bukkit.inventory.ItemStack source, final org.bukkit.inventory.ItemStack target, final int amount) {
        final ItemStack src = CraftItemStack.unwrap(source);
        final ItemStack dst = CraftItemStack.unwrap(target);
        if (src.isEmpty() || dst.isEmpty() || amount <= 0) {
            target.setAmount(target.getAmount() + amount);
            source.setAmount(source.getAmount() - amount);
            return;
        }
        dst.growFrom(src, amount);
        src.shrink(amount);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Сохранение

    /** Что писать в поле {@code item_serials}. */
    public static Optional<long[]> encodeView(final ItemStack stack) {
        final long[] serials = stack.serials;
        if (!ENABLED || serials == null || stack.isEmpty() || !isValid(serials, stack.getCount())) {
            return Optional.empty();
        }
        return Optional.of(serials);
    }

    /** Ставит прочитанные из сохранения номера; некорректные молча отбрасываются. */
    public static ItemStack attach(final ItemStack stack, final Optional<long[]> serials) {
        if (ENABLED && serials.isPresent() && !stack.isEmpty()) {
            final long[] value = serials.get();
            if (isValid(value, stack.getCount())) {
                stack.serials = normalize(value);
            }
        }
        return stack;
    }

    private static boolean isValid(final long[] serials, final int count) {
        if ((serials.length & 1) != 0) {
            return false;
        }
        long total = 0;
        for (int i = 0; i < serials.length; i += 2) {
            if (serials[i] < UNTRACKED || serials[i + 1] <= 0) {
                return false;
            }
            total += serials[i + 1];
        }
        return total == count;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Хуки из ItemStack

    /** Вызывается из {@link ItemStack#copy(boolean)}: копия получает те же номера и общий {@link Claim}. */
    public static void onCopy(final ItemStack original, final ItemStack copy) {
        if (original.serials == null) {
            return;
        }
        if (!ENABLED) {
            original.serials = null;
            return;
        }
        copy.serials = original.serials;
        copy.serialClaim = claim(original);
    }

    /** Вызывается из {@link ItemStack#setCount} до изменения количества. */
    public static void onSetCount(final ItemStack stack, final int oldCount, final int newCount) {
        final long[] serials = stack.serials;
        if (serials == null || oldCount == newCount) {
            return;
        }
        if (!ENABLED || newCount <= 0 || oldCount <= 0) {
            clear(stack);
            return;
        }
        if (newCount > oldCount) {
            // источник прибавки неизвестен: новые единицы без номеров
            stack.serials = concat(serials, oldCount, null, newCount - oldCount);
            return;
        }
        final Claim claim = claim(stack);
        synchronized (claim) {
            claim.refresh();
            final Choice choice = choose(serials, oldCount, newCount, claim.othersOf(stack, null), claim.released);
            claim.released = choice.releasedPool;
            claim.hold(stack, choice.chosen);
            claim.release(choice.released);
            stack.serials = choice.chosen;
        }
    }

    /**
     * Выбирает у {@code source} {@code amount} единиц для {@code target}. {@code source} при этом не меняется, но
     * выбранные единицы закрепляются за {@code target}, поэтому следующее уменьшение {@code source} в этом тике оставит ему остальные.
     */
    public static long @Nullable [] give(final ItemStack source, final ItemStack target, final int amount) {
        final long[] serials = source.serials;
        if (!ENABLED || serials == null || amount <= 0 || source.isEmpty()) {
            return null;
        }
        final int sourceCount = source.getCount();
        final int take = Math.min(amount, sourceCount);
        final Claim claim = claim(source);
        final long[] given;
        synchronized (claim) {
            claim.refresh();
            // сам source из исключений не убираем: его прошлые решения в этом тике не должны мешать
            final Choice choice = choose(serials, sourceCount, take, claim.othersOf(source, target), null);
            given = choice.chosen;
            claim.hold(target, given);
        }
        return take < amount ? concat(given, take, null, amount - take) : given;
    }

    /**
     * Перед временным уменьшением количества (ванилла потом его восстанавливает). Ставит стаку первые {@code tempCount}
     * единиц без побочных эффектов и возвращает полный набор номеров для {@link #restoreCount}.
     */
    public static long @Nullable [] beginTemporaryCount(final ItemStack stack, final int tempCount) {
        final long[] saved = stack.serials;
        final int oldCount = stack.getCount();
        stack.setCountRaw(tempCount);
        if (saved != null) {
            stack.serials = ENABLED && tempCount > 0 ? slice(saved, oldCount, 0, tempCount) : null;
        }
        return saved;
    }

    /** Возвращает количество и номера, сохранённые {@link #beginTemporaryCount}. */
    public static void restoreCount(final ItemStack stack, final int count, final long @Nullable [] saved) {
        stack.setCountRaw(count);
        stack.serials = ENABLED && saved != null && count > 0 && isValid(saved, count) ? saved : null;
    }

    /**
     * Для оптимизированных воронок Paper: в слоте воронки остаётся {@code remaining} (что не ушло) плюс единицы,
     * которые не брались в перенос ({@code movedCount .. originalCount} из {@code saved}).
     */
    public static void hopperRemainder(
        final ItemStack newStack, final long[] saved, final int originalCount, final int movedCount, final ItemStack remaining
    ) {
        final int remainingCount = remaining.getCount();
        final int newCount = originalCount - movedCount + remainingCount;
        detach(newStack);
        newStack.setCountRaw(newCount);
        if (!ENABLED || newCount <= 0) {
            newStack.serials = null;
            return;
        }
        final long[] rest = slice(saved, originalCount, movedCount, originalCount - movedCount);
        newStack.serials = concat(remainingCount > 0 ? remaining.serials : null, remainingCount, rest, originalCount - movedCount);
    }

    /**
     * Для перетаскивания (drag): стак в слоте = {@code existing} (то, что уже лежало, {@code existingCount} шт.) +
     * {@code placed} (то, что положили из курсора).
     */
    public static void composeDrag(final ItemStack placed, final int placedCount, final ItemStack existing, final int existingCount, final boolean clone) {
        if (!ENABLED) {
            return;
        }
        final long[] placedSerials = clone ? null : placed.serials;
        final long[] existingSerials = existingCount > 0 ? existing.serials : null;
        // claim не отвязываем: за placed закреплена его доля курсора, и остаток курсора должен её обойти
        placed.setCountRaw(existingCount + placedCount);
        placed.serials = concat(existingSerials, existingCount, placedSerials, placedCount);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Claim: общий учёт номеров у копий одного стака в пределах тика

    public static final class Claim {
        private int tick = Integer.MIN_VALUE;
        private final ArrayList<ItemStack> owners = new ArrayList<>(2);
        private final ArrayList<long[]> held = new ArrayList<>(2);
        /** Номера, которые стаки этого claim отпустили в этом тике (только с номерами, без -1). */
        private long @Nullable [] released;

        private void refresh() {
            final int now = MinecraftServer.currentTick;
            if (now != this.tick) {
                this.tick = now;
                this.owners.clear();
                this.held.clear();
                this.released = null;
            }
        }

        private void hold(final ItemStack owner, final long @Nullable [] serials) {
            for (int i = 0; i < this.owners.size(); i++) {
                if (this.owners.get(i) == owner) {
                    if (serials == null) {
                        this.owners.remove(i);
                        this.held.remove(i);
                    } else {
                        this.held.set(i, serials);
                    }
                    return;
                }
            }
            if (serials != null) {
                this.owners.add(owner);
                this.held.add(serials);
            }
        }

        private void release(final long @Nullable [] serials) {
            if (serials != null) {
                this.released = this.released == null ? serials : concat(this.released, (int) total(this.released), serials, (int) total(serials));
            }
        }

        /** Отрезки {@code [from, to)}, закреплённые за другими стаками, отсортированные и без пересечений. */
        private long[] othersOf(final ItemStack self, final @Nullable ItemStack alsoSkip) {
            final LongArrayList intervals = new LongArrayList();
            for (int i = 0; i < this.owners.size(); i++) {
                final ItemStack owner = this.owners.get(i);
                if (owner == self || owner == alsoSkip) {
                    continue;
                }
                final long[] serials = this.held.get(i);
                for (int j = 0; j < serials.length; j += 2) {
                    if (serials[j] != UNTRACKED) {
                        intervals.add(serials[j]);
                        intervals.add(serials[j] + serials[j + 1]);
                    }
                }
            }
            return mergeIntervals(intervals.toLongArray());
        }
    }

    private static Claim claim(final ItemStack stack) {
        Claim claim = stack.serialClaim;
        if (claim == null) {
            claim = new Claim();
            stack.serialClaim = claim;
        }
        return claim;
    }

    private static void detach(final ItemStack stack) {
        final Claim claim = stack.serialClaim;
        if (claim != null) {
            synchronized (claim) {
                claim.hold(stack, null);
            }
            stack.serialClaim = null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Операции с наборами номеров

    private record Choice(long @Nullable [] chosen, long @Nullable [] released, long @Nullable [] releasedPool) {
    }

    /**
     * Оставляет {@code keep} единиц из {@code serials} (стак на {@code count} единиц).
     * Порядок предпочтения: единицы, не закреплённые за другими; затем номера, отпущенные в этом тике ({@code pool});
     * затем закреплённые (это уже реальное совпадение номеров).
     */
    private static Choice choose(final long[] serials, final int count, final int keep, final long[] others, final long @Nullable [] pool) {
        // куски стака по порядку: (start, len, free)
        final LongArrayList pieces = new LongArrayList();
        long covered = 0;
        for (int i = 0; i < serials.length; i += 2) {
            final long start = serials[i];
            final long len = serials[i + 1];
            covered += len;
            if (start == UNTRACKED) {
                addPiece(pieces, UNTRACKED, len, true);
                continue;
            }
            long cursor = start;
            final long end = start + len;
            for (int j = 0; j < others.length && cursor < end; j += 2) {
                final long from = others[j];
                final long to = others[j + 1];
                if (to <= cursor || from >= end) {
                    continue;
                }
                if (from > cursor) {
                    addPiece(pieces, cursor, from - cursor, true);
                }
                final long conflictEnd = Math.min(to, end);
                addPiece(pieces, Math.max(from, cursor), conflictEnd - Math.max(from, cursor), false);
                cursor = conflictEnd;
            }
            if (cursor < end) {
                addPiece(pieces, cursor, end - cursor, true);
            }
        }
        if (covered < count) {
            addPiece(pieces, UNTRACKED, count - covered, true);
        }

        final LongArrayList chosen = new LongArrayList();
        final LongArrayList released = new LongArrayList();
        long need = keep;
        final boolean[] taken = new boolean[pieces.size() / 3];
        // 1. свободные
        for (int p = 0; p < pieces.size(); p += 3) {
            if (pieces.getLong(p + 2) == 1L) {
                need = takePiece(pieces, p, need, chosen, released);
                taken[p / 3] = true;
            }
        }
        // 2. отпущенные в этом тике
        long[] remainingPool = pool;
        if (need > 0 && pool != null) {
            final long poolTotal = total(pool);
            final long fromPool = Math.min(need, poolTotal);
            final long[] head = slice(pool, (int) poolTotal, 0, (int) fromPool);
            if (head != null) {
                chosen.addElements(chosen.size(), head);
            }
            remainingPool = slice(pool, (int) poolTotal, (int) fromPool, (int) (poolTotal - fromPool));
            need -= fromPool;
        }
        // 3. закреплённые за другими
        for (int p = 0; p < pieces.size(); p += 3) {
            if (need == 0) {
                break;
            }
            if (!taken[p / 3]) {
                need = takePiece(pieces, p, need, chosen, null);
            }
        }
        return new Choice(normalize(chosen.toLongArray()), normalize(released.toLongArray()), remainingPool);
    }

    private static long takePiece(final LongArrayList pieces, final int p, final long need, final LongArrayList chosen, final @Nullable LongArrayList released) {
        final long start = pieces.getLong(p);
        final long len = pieces.getLong(p + 1);
        final long take = Math.min(need, len);
        if (take > 0) {
            chosen.add(start);
            chosen.add(take);
        }
        if (released != null && take < len && start != UNTRACKED) {
            released.add(start + take);
            released.add(len - take);
        }
        return need - take;
    }

    private static void addPiece(final LongArrayList pieces, final long start, final long len, final boolean free) {
        if (len > 0) {
            pieces.add(start);
            pieces.add(len);
            pieces.add(free ? 1L : 0L);
        }
    }

    /** Единицы {@code [from, from + n)} стака на {@code count} единиц. Чего не хватает до {@code count}, считается без номеров. */
    public static long @Nullable [] slice(final long @Nullable [] serials, final int count, final int from, final int n) {
        if (serials == null || n <= 0) {
            return null;
        }
        final LongArrayList out = new LongArrayList();
        long position = 0;
        final long end = (long) from + n;
        for (int i = 0; i < serials.length && position < end; i += 2) {
            final long start = serials[i];
            final long len = serials[i + 1];
            final long pieceFrom = Math.max(position, from);
            final long pieceTo = Math.min(position + len, end);
            if (pieceTo > pieceFrom) {
                out.add(start == UNTRACKED ? UNTRACKED : start + (pieceFrom - position));
                out.add(pieceTo - pieceFrom);
            }
            position += len;
        }
        if (position < end) {
            out.add(UNTRACKED);
            out.add(end - Math.max(position, from));
        }
        return normalize(out.toLongArray());
    }

    /** {@code a} ({@code aCount} единиц), затем {@code b} ({@code bCount} единиц). {@code null} = единицы без номеров. */
    public static long @Nullable [] concat(final long @Nullable [] a, final int aCount, final long @Nullable [] b, final int bCount) {
        if (a == null && b == null) {
            return null;
        }
        final LongArrayList out = new LongArrayList();
        appendPart(out, a, aCount);
        appendPart(out, b, bCount);
        return normalize(out.toLongArray());
    }

    private static void appendPart(final LongArrayList out, final long @Nullable [] part, final int count) {
        if (count <= 0) {
            return;
        }
        if (part == null) {
            out.add(UNTRACKED);
            out.add(count);
            return;
        }
        final long[] exact = slice(part, count, 0, count);
        if (exact == null) {
            out.add(UNTRACKED);
            out.add(count);
        } else {
            out.addElements(out.size(), exact);
        }
    }

    /** Склеивает соседние куски; {@code null}, если номеров нет совсем. */
    private static long @Nullable [] normalize(final long[] serials) {
        final LongArrayList out = new LongArrayList(serials.length);
        boolean anyTracked = false;
        for (int i = 0; i < serials.length; i += 2) {
            final long start = serials[i];
            final long len = serials[i + 1];
            if (len <= 0) {
                continue;
            }
            anyTracked |= start != UNTRACKED;
            final int size = out.size();
            if (size > 0) {
                final long lastStart = out.getLong(size - 2);
                final long lastLen = out.getLong(size - 1);
                if ((start == UNTRACKED && lastStart == UNTRACKED) || (start != UNTRACKED && lastStart != UNTRACKED && lastStart + lastLen == start)) {
                    out.set(size - 1, lastLen + len);
                    continue;
                }
            }
            out.add(start);
            out.add(len);
        }
        return anyTracked ? out.toLongArray() : null;
    }

    private static long[] mergeIntervals(final long[] intervals) {
        final int n = intervals.length / 2;
        if (n <= 1) {
            return intervals;
        }
        final long[][] pairs = new long[n][];
        for (int i = 0; i < n; i++) {
            pairs[i] = new long[]{intervals[i * 2], intervals[i * 2 + 1]};
        }
        Arrays.sort(pairs, (x, y) -> Long.compare(x[0], y[0]));
        final LongArrayList out = new LongArrayList();
        for (final long[] pair : pairs) {
            final int size = out.size();
            if (size > 0 && pair[0] <= out.getLong(size - 1)) {
                out.set(size - 1, Math.max(out.getLong(size - 1), pair[1]));
            } else {
                out.add(pair[0]);
                out.add(pair[1]);
            }
        }
        return out.toLongArray();
    }

    private static long total(final long @Nullable [] serials) {
        if (serials == null) {
            return 0;
        }
        long total = 0;
        for (int i = 1; i < serials.length; i += 2) {
            total += serials[i];
        }
        return total;
    }
}
