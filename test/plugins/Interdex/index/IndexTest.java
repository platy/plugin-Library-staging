/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Interdex.index;

import junit.framework.TestCase;

import plugins.Interdex.util.*;
import plugins.Interdex.serl.*;
import plugins.Interdex.serl.Serialiser.*;
import plugins.Interdex.index.*;

import freenet.keys.FreenetURI;

import java.util.*;

/**
** @author infinity0
*/
public class IndexTest extends TestCase {

	static {
		//plugins.Interdex.serl.YamlArchiver.setTestMode();
	}

	public String rndStr() {
		return java.util.UUID.randomUUID().toString();
	}

	long time = 0;

	public long timeDiff() {
		long oldtime = time;
		time = System.currentTimeMillis();
		return time - oldtime;
	}

	IndexFileSerialiser.PrefixTreeMapSerialiser<Token, SortedSet<TokenEntry>> srl = new
	IndexFileSerialiser.PrefixTreeMapSerialiser<Token, SortedSet<TokenEntry>>(new IndexFileSerialiser.TokenTranslator());

	IndexFileSerialiser.TokenEntrySerialiser vsrl = new
	IndexFileSerialiser.TokenEntrySerialiser();

	SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>> test;

	Random rand = new Random();

	Set<String> randomWords = new HashSet<String>(Arrays.asList(
		"Lorem", "ipsum", "dolor", "sit", "amet,", "consectetur", "adipisicing",
		"elit,", "sed", "do", "eiusmod", "tempor", "incididunt", "ut", "labore",
		"et", "dolore", "magna", "aliqua.", "Ut", "enim", "ad", "minim",
		"veniam,", "quis", "nostrud", "exercitation", "ullamco", "laboris", "nisi",
		"ut", "aliquip", "ex", "ea", "commodo", "consequat.", "Duis", "aute",
		"irure", "dolor", "in", "reprehenderit", "in", "voluptate", "velit",
		"esse", "cillum", "dolore", "eu", "fugiat", "nulla", "pariatur.",
		"Excepteur", "sint", "occaecat", "cupidatat", "non", "proident,", "sunt",
		"in", "culpa", "qui", "officia", "deserunt", "mollit", "anim", "id", "est",
		"laborum."
	));

	protected void newTestSkeleton() {
		test = new SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>(new Token(), 512);
		test.setSerialiser(srl, vsrl);
		timeDiff();
	}

	public void fullInflate() {
		newTestSkeleton();

		int totalentries = 0;

		for (int i=0; i<256; ++i) {
			String key = rndStr().substring(0,8);
			SortedSet<TokenEntry> entries = new TreeSet<TokenEntry>();
			int n = rand.nextInt(16) + 16;
			totalentries += n;

			try {
				for (int j=0; j<n; ++j) {
					TokenEntry e = new TokenURIEntry(key, new FreenetURI("CHK@" + rndStr().replace('-', 'Z')));
					e.setRelevance((float)Math.random());
					entries.add(e);
				}
			} catch (java.net.MalformedURLException e) {
				// should not happen
				throw new RuntimeException(e);
			}

			test.put(new Token(key), entries);
		}
		System.out.print(totalentries + " entries generated in " + timeDiff() + " ms, ");

		test.deflate();
		assertTrue(test.isBare());
		assertFalse(test.isLive());
		PushTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>> task = new
		PushTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>>(test);
		srl.push(task);
		System.out.print("deflated in " + timeDiff() + " ms, ");

		PullTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>> tasq = new
		PullTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>>(task.meta);
		srl.pull(tasq);
		test.inflate();
		assertTrue(test.isLive());
		assertFalse(test.isBare());
		System.out.println("inflated in " + timeDiff() + " ms");
	}

	public void testBasicMulti() {
		int n = 8;
		for (int i=0; i<n; ++i) {
			System.out.print(i + "/" + n + ": ");
			fullInflate();
		}
	}

	public void partialInflate() {
		newTestSkeleton();
		int totalentries = 0;

		for (String word: randomWords) {
			SortedSet<TokenEntry> entries = new TreeSet<TokenEntry>();
			int n = rand.nextInt(16) + 16;
			totalentries += n;

			try {
				for (int j=0; j<n; ++j) {
					TokenEntry e = new TokenURIEntry(word, new FreenetURI("CHK@" + rndStr().replace('-', 'Z')));
					e.setRelevance((float)Math.random());
					entries.add(e);
				}
			} catch (java.net.MalformedURLException e) {
				// pass
			}

			test.put(new Token(word), entries);
		}

		System.out.print(totalentries + " entries generated in " + timeDiff() + " ms, ");

		test.deflate();
		assertTrue(test.isBare());
		assertFalse(test.isLive());
		PushTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>> task = new
		PushTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>>(test);
		srl.push(task);
		System.out.print("deflated in " + timeDiff() + " ms, ");

		PullTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>> tasq = new
		PullTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>>(task.meta);
		srl.pull(tasq);

		for (String s: randomWords) {
			//assertFalse(test.isLive()); // might be live if inflate(key) inflates some other keys too
			Token t = Token.intern(s);
			test.inflate(t);
			test.get(t);
			assertFalse(test.isBare());
		}
		assertTrue(test.isLive());
		assertFalse(test.isBare());
		System.out.println("inflated all terms separately in " + timeDiff() + " ms");

	}

	public void testPartialInflateMulti() {
		int n = 8;
		for (int i=0; i<n; ++i) {
			System.out.print(i + "/" + n + ": ");
			partialInflate();
		}
	}

	public void testProgress() {
		newTestSkeleton();

		int totalentries = 0;
		int numterms = 256;
		int save = rand.nextInt(numterms);
		String sterm = null;

		System.out.println("Generating a shit load of entries to test progress polling. This may take a while...");
		for (int i=0; i<numterms; ++i) {
			String key = rndStr().substring(0,8);
			if (i == save) { sterm = key; }
			SortedSet<TokenEntry> entries = new TreeSet<TokenEntry>();
			int n = rand.nextInt(512) + 512;
			totalentries += n;

			try {
				for (int j=0; j<n; ++j) {
					TokenEntry e = new TokenURIEntry(key, new FreenetURI("CHK@" + rndStr().replace('-', 'Z')));
					e.setRelevance((float)Math.random());
					entries.add(e);
				}
			} catch (java.net.MalformedURLException e) {
				// should not happen
				throw new RuntimeException(e);
			}

			test.put(new Token(key), entries);
		}
		System.out.print(totalentries + " entries generated in " + timeDiff() + " ms, ");

		test.deflate();
		assertTrue(test.isBare());
		assertFalse(test.isLive());
		PushTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>> task = new
		PushTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>>(test);
		srl.push(task);
		System.out.println("deflated in " + timeDiff() + " ms");

		plugins.Interdex.serl.YamlArchiver.setTestMode();
		PullTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>> tasq = new
		PullTask<SkeletonPrefixTreeMap<Token, SortedSet<TokenEntry>>>(task.meta);
		srl.pull(tasq);

		final String s = sterm;
		new Thread() {
			public void run() {
				test.inflate(Token.intern(s));
			}
		}.start();

		for (;;) {
			try {
				test.get(Token.intern(sterm));
				System.out.println("deflated term \"" + sterm + "\" in " + timeDiff() + "ms.");
				break;
			} catch (DataNotLoadedException e) {
				Object meta = e.getValue();
				Progress p;
				if ((p = srl.getTracker().getPullProgress(meta)) != null) {
					pollProgress(meta, p);
				} else if ((p = vsrl.getTracker().getPullProgress(meta)) != null) {
					pollProgress(meta, p);
				} else {
					System.out.println("lol wut no progress (" + meta + ")? trying again");
					try { Thread.sleep(1000); } catch (InterruptedException x) { }
				}
				continue;
			}
		}

	}

	public void pollProgress(Object key, Progress p) {
		int d; int t; boolean f;
		do {
			d = p.partsDone();
			t = p.partsTotal();
			f = p.isTotalFinal();
			System.out.println(key + ": " + d + "/" + t + (f? "": "???"));
			try { Thread.sleep(1000); } catch (InterruptedException x) { }
		} while (!f || d != t);
	}

}
