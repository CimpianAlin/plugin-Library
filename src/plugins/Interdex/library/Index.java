/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Interdex.library;

import plugins.Interdex.index.TokenEntry;
import plugins.Interdex.index.URIEntry;
import plugins.Interdex.index.Request;

import freenet.keys.FreenetURI;

import java.util.Collection;

/**
** Represents the data for an index.
**
** @author infinity0
*/
public interface Index {

	/**
	** Fetch the TokenEntries associated with a given term.
	**
	** DOCUMENT
	*/
	public Request<Collection<TokenEntry>> getTermEntries(String term);

	public Request<URIEntry> getURIEntry(FreenetURI uri);

}
