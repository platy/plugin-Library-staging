/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Library.search;

import plugins.Library.index.URIWrapper;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * TODO Set which makes sure all data in results being combined is retained
 * FIXME get phrase search to work
 * @author MikeB
 */
public class ResultSet extends TreeSet<URIWrapper>{

	/**
	 * Create new URIWrappers in this ResultSet for each instance of this result being followed by result
	 * @param result
	 * @throws Exception if URIWrappers do not contain termpositions
	 */
	public void retainFollowed(Set<URIWrapper> result) throws Exception {
		TreeSet<URIWrapper> tempSet = new TreeSet();
		for (Iterator<URIWrapper> it = result.iterator(); it.hasNext();) {
			URIWrapper uriWrapper = it.next();
			for (URIWrapper uriWrapper2 : result) {
				URIWrapper phrasePos = uriWrapper.followedby(uriWrapper2);
				if(phrasePos!=null)
					tempSet.add(phrasePos);
			}
		}
		this.clear();
		this.addAll(tempSet);
	}
}
