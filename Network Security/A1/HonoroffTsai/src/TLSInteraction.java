/* This code is responsible for forming every message in the TLS handshake as well as sending and receiving application data. */

package simnet;

import org.bouncycastle.util.encoders.Hex;
import javax.security.cert.*;
import java.io.*;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.jce.X509Principal;
import java.security.interfaces.*;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.*;
import java.math.*;
import java.util.*;
import java.security.*;

public class TLSInteraction extends TLS {
	/**
	 *  Represents the server's certificate, read in from a file
	 */
	public X509Certificate cert = null;

	/**
	 *  Holds the ciphersuite the client is going to use
	 */
	byte ciphersuite[];

	/**
	 *  Whether this handshake is client-side
	 */
	boolean client;

	/**
	 *  The version of TLS
	 */
	byte version[] = { TLS.VERSIONMAJOR, TLS.VERSIONMINOR };

	/**
	 *  The client's random bytes
	 */
	byte clientrandom[];

	/**
	 *  The server's random bytes
	 */
	byte serverrandom[];

	/**
	 *  The session id for this session (used in caching)
	 */
	byte sessionid[] = null;

	/**
	 *  Holds the cipher suite the server selects
	 */
	byte serverSuite[];

	/**
	 *  Holds the cipher suite the client is willing to use
	 */
	byte clientSuite[];

	/**
	 *  Houses the keys used in RSA encryption/decryption
	 */
	RSAKeyParameters publickey;

	/**
	 *  The master secret
	 */
	byte mastersecret[] = null;

	/**
	 *  The premaster secret
	 */
	byte premastersecret[];

	/**
	 *  The client mac key
	 */
	byte client_mac_key[];

	/**
	 *  The server mac key
	 */
	byte server_mac_key[];

	/**
	 *  The client encryption key
	 */
	byte client_write_key[];

	/**
	 *  The server encryption key
	 */
	byte server_write_key[];

	/**
	 *  The client initialization vectors
	 */
	byte client_write_iv[];

	/**
	 * The server initialization vectors
	 */
	byte server_write_iv[];

	/**
	 *  Holds all the handshake messages seen by both sides thus far
	 */
	byte allhandshakes[];

	/**
	 *  The node that owns this TLSInteraction object (for printing)
	 */
	Node node;

	/**
	 *  An instance of the crypto engine
	 */
	SimnetCryptoEngine sce;

	/**
	*  Contains the instance of the ciphersuite currently being used
	*/
	public TLSCipherSuite tlsciphersuite = null;

	/**
	 * The last packet seen by this implementation.  This is used as the IV
	 */
	byte[] lastpacket;

	/**
	 * Forms the keys from the premaster secret
	 */
	public void formKeys() {
		if (mastersecret == null) {
			mastersecret = new byte[48];
			byte[] result = sce.PRF(premastersecret, "master secret", sce.bytecat(clientrandom, serverrandom));
			for (int i = 0; i < 48; i++) { mastersecret[i] = result[i]; }
		}

		byte[] keyBlock = sce.PRF(mastersecret, "key expansion", sce.bytecat(clientrandom, serverrandom));

		client_mac_key = new byte[16];
		server_mac_key = new byte[16];
		client_write_key = new byte[8];
		server_write_key = new byte[8];
		client_write_iv = new byte[8];
		server_write_iv = new byte[8];

		for (int i = 0; i < 16; i++) { client_mac_key[i] = keyBlock[i]; }
		for (int i = 0; i < 16; i++) { server_mac_key[i] = keyBlock[i + 16]; }
		for (int i = 0; i < 8; i++) { client_write_key[i] = keyBlock[i + 32]; }
		for (int i = 0; i < 8; i++) { server_write_key[i] = keyBlock[i + 40]; }
		for (int i = 0; i < 8; i++) { client_write_iv[i] = keyBlock[i + 48]; }
		for (int i = 0; i < 8; i++) { server_write_iv[i] = keyBlock[i + 56]; }

		return;
	}

	/**
	 * Makes the client key exchange msg
	 *
	 * @return byte[] - a byte array containing the client key exchange msg
	 */
	public byte[] formClientKeyExchange() {
		byte[] packet = new byte[0];
		byte[] recordHeader = formRecordHeader(TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR, TLS.HANDSHAKELENGTH + SimnetCryptoEngine.RSA_OUTPUT_SIZE);
		byte[] handshakeHeader = formHandshakeHeader(TLS.CLIENTKEYEXCHANGE, SimnetCryptoEngine.RSA_OUTPUT_SIZE);

		byte[] randomBytes = new byte[48];
		sce.getRandomBytes(randomBytes);
		randomBytes[0] = TLS.VERSIONMAJOR;
		randomBytes[1] = TLS.VERSIONMINOR;

		premastersecret = randomBytes;
		byte[] encrypted = sce.encryptRSA(randomBytes, publickey);

		packet = recordHeader;
		packet = sce.bytecat(packet, handshakeHeader);
		packet = sce.bytecat(packet, encrypted);

		return packet;
	}

	/**
	 * Interpret the received ClientKeyExchange
	 *
	 * @param bytestr - a bytestr with the ClientKeyExchange msg
	 */
	public void interpretClientKeyExchange(byte[] bytestr, BigInteger privkey, BigInteger modulus) throws TLSException {
		byte[] encrypted = new byte[SimnetCryptoEngine.RSA_OUTPUT_SIZE];
		for (int i = 0; i < SimnetCryptoEngine.RSA_OUTPUT_SIZE; i++) { encrypted[i] = bytestr[TLS.RECORDLENGTH + TLS.HANDSHAKELENGTH + i]; }

		try {
			byte[] randomBytes = sce.decryptRSA(encrypted, new RSAKeyParameters(true, modulus, privkey));
			premastersecret = randomBytes;
		} catch (Exception e) {
			throw new TLSException(e.toString());
		}

		return;
	}

	/**
	 * Forms the application data (for after the handshake)
	 *
	 * @param o - object to serialize and encapsulate in the header
	 * @return byte[] - a byte string containing the application data msg
	 */
	public byte[] formApplicationData(Object o) {
		byte[] write_key;
		byte[] mac_key;

		if (client) {
			write_key = client_write_key;
			mac_key = client_mac_key;
		} else {
			write_key = server_write_key;
			mac_key = server_mac_key;
		}

		byte[] serialized = sce.serialize(o);
		byte[] encrypted = tlsciphersuite.encrypt(write_key, serialized, lastpacket);
		lastpacket = encrypted;
		byte[] HMacSHA1 = sce.HMacSHA1(encrypted, mac_key);
		byte[] recordHeader = formRecordHeader(TLS.APPLICATIONDATA, TLS.VERSIONMAJOR, TLS.VERSIONMINOR, encrypted.length + HMacSHA1.length);

		byte[] packet = recordHeader;
		packet = sce.bytecat(packet, encrypted);
		packet = sce.bytecat(packet, HMacSHA1);

		return packet;
	}

	/**
	 * Interpret the received application data
	 *
	 * @param bytestr - a bytestr with the application data
	 * @return Object - the recombined object if it passes verification, null if not
	 */
	public Object interpretApplicationData(byte[] bytestr) throws TLSException {
		byte[] write_key;
		byte[] mac_key;

		if (client) {
			write_key = server_write_key;
			mac_key = server_mac_key;
		} else {
			write_key = client_write_key;
			mac_key = client_mac_key;
		}

		int dataLength = checkRecordHeader(bytestr, TLS.APPLICATIONDATA, TLS.VERSIONMAJOR, TLS.VERSIONMINOR);
		int encryptedLength = dataLength - sce.SHA1_DIGEST_SIZE;

		byte[] encrypted = new byte[encryptedLength];
		byte[] HMacSHA1 = new byte[sce.SHA1_DIGEST_SIZE];

		for (int i = 0; i < encryptedLength; i++) { encrypted[i] = bytestr[TLS.RECORDLENGTH + i]; }
		for (int i = 0; i < sce.SHA1_DIGEST_SIZE; i++) { HMacSHA1[i] = bytestr[TLS.RECORDLENGTH + encryptedLength + i]; }

		if (!sce.isEqual(sce.HMacSHA1(encrypted, mac_key), HMacSHA1)) { throw new TLSException("MAC check failed!"); }
		Object object = null;

		try {
			byte[] serialized = tlsciphersuite.decrypt(write_key, encrypted, lastpacket);
			lastpacket = encrypted;
			object = sce.deserialize(serialized);
		} catch (Exception e) {
			throw new TLSException(e.toString());
		}

		return object;
	}

	/**
	 * Forms the record header for a given msg
	 *
	 * @param msgtype - the msgtype to put in the header
	 * @param versionmajor - the most significant byte of the version
	 * @param versionminor - the least significant byte of the version
	 * @param recordlen - the length of the record (must fit in 2 bytes)
	 * @return byte[] - a 5 byte byte array with the record header
	 */
	public byte[] formRecordHeader(byte msgtype, byte versionmajor, byte versionminor, int recordlen)
	{
		byte out[] = new byte[5];

		out[0] = msgtype;
		out[1] = versionmajor;
		out[2] = versionminor;

		byte len[] = new byte[2];
		len = TLS.makeTwoByteLength(recordlen);
		out[3] = len[0];
		out[4] = len[1];

		return out;
	}

	/**
	 * Forms the record header for a given msg
	 *
	 * @param handshake - the handshaketype to put in the header
	 * @param handshakelen - the length of the handshake (must fit in 3 bytes)
	 * @return byte[] - a 4 byte byte array with the handshake header
	 */
	byte [] formHandshakeHeader(byte handshaketype, int handshakelen)
	{
		byte out[] = new byte[4];
		out[0] = handshaketype;
		byte len[] = TLS.makeThreeByteLength(handshakelen);
		out[1] = len[0];
		out[2] = len[1];
		out[3] = len[2];

		return out;
	}

	/**
	 * Form the clienthello for the owner of this object.
	 *
	 * @return byte[] - byte string with the clienthello msg
	 */
	public byte[] formClientHello() {
		int i = 0, k;
		byte CM[] = { 0 };

		/* clientrandom.length + version.length + ciphersuite.length + a 2 byte length of the cipher suite list + 1 byte length for the compression method list + 1 byte for the compression method +
		   a one byte length of the session id field */
		int msglength = clientrandom.length + version.length + ciphersuite.length + 2 + 1 + 1 + 1;

		// if this session is cached, we need to have a longer message
		if (sessionid != null)
			msglength+=sessionid.length;

		byte[] recordheader = formRecordHeader(TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR, msglength + 4);
		byte[] handshakeheader = formHandshakeHeader(TLS.CLIENTHELLO, msglength);

		node.printout(1, node.id, "The msglength of this client hello: " + msglength);
		byte[] ret = new byte[msglength + recordheader.length + handshakeheader.length];

		// put in the record header
		i+=copyin(ret, recordheader, i, recordheader.length);

		// put in the handshake header
		i+=copyin(ret, handshakeheader, i, handshakeheader.length);

		// put in the version
		i+=copyin(ret, version, i, version.length);

		// put in the client's random bytes
		i+=copyin(ret, clientrandom, i, clientrandom.length);

		// put in the session id if this is a continued session
		if (sessionid != null) {
			ret[i++] = (byte)sessionid.length;
			i+=copyin(ret, sessionid, i, sessionid.length);
		} else {
			ret[i++] = 0;
		}

		// convert the length of the supported cipher suites into a 2 byte length
		byte[] len2 = TLS.makeTwoByteLength(ciphersuite.length);

		// copy in the length of the ciphersuite
		i+=copyin(ret, len2, i, len2.length);

		// copy in the actual cipher suite
		i+=copyin(ret, ciphersuite, i, ciphersuite.length);

		// the length of the supported compression methods(always 1)
		byte[] len3 = { 0x01 };
		i+=copyin(ret, len3, i, len3.length);

		// Compression mode, always 0
		i+=copyin(ret, CM, i, CM.length);

		return ret;
	}

	/**
	 * Interpret the received clienthello
	 *
	 * @param bytestr - a bytestr with the client's hello msg
	 */
	public void interpretClientHello(byte bytestr[]) throws TLSException {
		int type = bytestr[0];
		int i, k;
		int recordlength, handshakelength;
		recordlength = checkRecordHeader(bytestr, TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR);
		handshakelength = checkHandshakeHeader(bytestr, TLS.CLIENTHELLO);

		clientrandom = new byte[32];

		// retrieve the client random bytes, skip the record header, the handshake header, and the version number
		for (i=0, k=TLS.RECORDLENGTH + TLS.HANDSHAKELENGTH + version.length;i<32;i++) {
			clientrandom[i] = bytestr[k++];
		}

		int sessionidlen = (int)bytestr[k++];

		// if the client sent a session id
		if (sessionidlen != 0) {
			sessionid = new byte[32];
			for (i=0;i < sessionidlen; i++) {
				sessionid[i] = bytestr[k++];
			}
			printbytes(5, node, "SESSIONID", sessionid);
		}

		int suiteLength = TLS.convertTwoByteLength (bytestr[k++], bytestr[k++]);

		node.printout(1, node.id, "Expect [" + suiteLength + "] bytes of cipher suite length from the client");

		// get the list of cipher suites
		clientSuite = new byte[suiteLength];
		for (i=0;i<suiteLength;i++) {
			clientSuite[i] = bytestr[k++];
		}
		serverSuite = new byte[2];

		// just select the first one for now..
		serverSuite[0] = clientSuite[0];
		serverSuite[1] = clientSuite[1];

		// set the cipher
		setCipher(ciphersuite);

		node.printout(1, node.id, "Cipher suite selected: " + serverSuite[0] + " and " + serverSuite[1]);
	}

	/**
	 * Makes the server hello msg
	 *
	 * @return byte[] - a byte string with the server hello msg
	 */
	public byte[] formServerHello() {
		// handshake length, 2 byte version, 32 bytes of randomness, 1 byte length of session id, 32 byte session id, 2 bytes of selected cipher suite, 1 byte compression method
		int recordlen = 4 + 2 + 32 + 1 + 32 + 2 + 1;

		byte recordheader[] = formRecordHeader (TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR, recordlen);
		byte handshakeheader[] = formHandshakeHeader (TLS.SERVERHELLO, recordlen-4);
		int i=0;

		byte ret[] = new byte[recordlen + 5];

		i+=copyin(ret, recordheader, i, recordheader.length);
		i+=copyin(ret, handshakeheader, i, handshakeheader.length);

		ret[i++] = TLS.VERSIONMAJOR;
		ret[i++] = TLS.VERSIONMINOR;

		i+=copyin(ret, serverrandom, i, serverrandom.length);

		ret[i++] = 0x20;

		if (sessionid == null) {
			sessionid = new byte[32];
			sce.getRandomBytes(sessionid);
		}
		i+=copyin(ret, sessionid, i, sessionid.length);

		ret[i++] = serverSuite[0];
		ret[i++] = serverSuite[1];

		// no compression
		ret[i++] = 0x00;

		return ret;
	}

	/**
	 * Interpret the received serverhello
	 *
	 * @param bytestr - a bytestr with the server's hello msg
	 */
	public void interpretServerHello(byte bytestr[]) throws TLSException {
		int type = bytestr[0];
		int i, k;
		int recordlength, handshakelength;

		// form the record and handshake header
		recordlength = checkRecordHeader(bytestr, TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR);
		handshakelength = checkHandshakeHeader(bytestr, TLS.SERVERHELLO);
		serverrandom = new byte[32];
		byte[] sid = new byte[32];

		// copy the server random
		for (i=0, k=11;i<32;i++)
		{
			serverrandom[i] = bytestr[k++];
		}

		int sessionidlen = (int)bytestr[k++];

		// copy the session id
		for (i=0;i < sessionidlen; i++)
		{
			sid[i] = bytestr[k++];
		}
		if (sessionid == null)
			sessionid = sid;
		else
		{
			// test the session id
			if (sid.length != sessionid.length)
				throw new TLSException ("interpretServerHello: Server does not recognize my cached session id!");
			for (i=0;i<sessionid.length;i++)
			{
				if (sessionid[i] != sid[i])
					throw new TLSException ("interpretServerHello: Server does not recognize my cached session id!");
			}
		}
		serverSuite = new byte[2];

		for (i=0;i<2;i++)
		{
			serverSuite[i] = bytestr[k++];
		}
		node.printout(1, node.id, "Cipher suite selected: " + serverSuite[0] + " and " + serverSuite[1]);

		// set the cipher suite
		setCipher(serverSuite);
	}

	/**
	 * Makes the Certificate msg of the handshake
	 *
	 * @return - the byte string with the formed certificate msg
	 */
	public byte[] formCertificate() throws TLSException {
		byte ret[] = null;
		int i=0, k;

		initCertificate();

		byte newin[] = sce.serialize(cert);
		byte recordheader[] = formRecordHeader(TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR, newin.length + 4);
		byte handshakeheader[] = formHandshakeHeader(TLS.CERTIFICATE, newin.length);

		// the + 1 is the byte for the number of certs in the list (always 1 in this implementation)
		ret = new byte[newin.length + handshakeheader.length + recordheader.length + 1];

		i+=copyin(ret, recordheader, i, recordheader.length);
		i+=copyin(ret, handshakeheader, i, handshakeheader.length);
		ret[i++] = 0;
		i+=copyin(ret, newin, i, newin.length);

		return ret;
	}

	/**
	 * Interpret the received certificate
	 *
	 * @param bytestr - a bytestr with the server's certificate msg
	 */
	public void interpretCertificate(byte[] bytestr) throws TLSException {
		int i, k=10, j;
		int recordlength, handshakelength;
		recordlength = checkRecordHeader(bytestr, TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR);
		handshakelength = checkHandshakeHeader(bytestr, TLS.CERTIFICATE);

		byte certificate[] = new byte[bytestr.length-k];

		// get the serialized certificate out of the packet, skip the record header, the handshake header, and the one byte length of certificates
		for (j=TLS.RECORDLENGTH+TLS.HANDSHAKELENGTH+1, i=0;j<bytestr.length;i++) {
			certificate[i] = bytestr[j++];
		}

		try {
			// recombine
			cert = (X509Certificate)sce.deserialize(certificate);

			// obtains the keys from the certificate
			RSAKey modulus = (RSAKey)cert.getPublicKey();
			RSAPublicKey exponent = (RSAPublicKey)cert.getPublicKey();
			publickey = new RSAKeyParameters (false, modulus.getModulus(), exponent.getPublicExponent());
			//debug msgs
			node.printout(1, node.id, "Exponent: " + exponent.getPublicExponent());
			node.printout(1, node.id, "Modulus : " + modulus.getModulus());
		} catch (Exception e) {
			throw new TLSException("Exception in interpretCertificate" + e);
		}
	}

	/**
	 * Forms the 'finished' msg for the client or the server
	 *
	 * @param label - the label to use in the formation of the keys
	 */
	public byte[] formFinished(String label) {
		byte md5sum[] = new byte[16];
		byte sha1sum[] = new byte[20];
		int i, k, j;
		byte writekey[] = null, mackey[] = null, iv[] = null;

		// calculate the necessary digests on all the msgs seen so far
		md5sum = sce.getMD5Digest(allhandshakes);
		sha1sum = sce.getSHA1Digest(allhandshakes);

		byte encthis[] = new byte[16];
		byte encd[] = null;

		// the result of the TLS psuedorandom function
		byte result[] = sce.PRF(mastersecret, label, sce.bytecat(md5sum, sha1sum));

		// form the to be encrypted array
		for (i=0;i<12;i++)
			encthis[i] = result[i];

		byte out[] = null;
		byte mac[] = null;

		printbytes(5, node, "ALLHANDSHAKES", allhandshakes);
		if (client) // use the client keys
		{
			writekey=client_write_key;
			mackey=client_mac_key;
			iv=client_write_iv;
		}
		else // use the server keys
		{
			writekey=server_write_key;
			mackey=server_mac_key;
			iv=server_write_iv;
		}

		node.printout(0, node.id, "The client key is: " + new String(Hex.encode(writekey)));
		out = tlsciphersuite.encrypt(writekey, encthis, iv);
		encd=new byte[out.length];

		for (j=0;j<out.length;j++)
			encd[j] = out[j];
		mac = sce.HMacSHA1(encd, mackey);

		// use this as the IV when next we encrypt
		lastpacket=encd;

		int length = encd.length + mac.length;

		byte recordheader[] = formRecordHeader (TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR, length + 4);
		byte handshakeheader[] = formHandshakeHeader(TLS.FINISHED, length - 20);
		byte finalmsg[] = new byte[length + recordheader.length + handshakeheader.length];

		i=0;
		i+=copyin(finalmsg, recordheader, i, recordheader.length);
		i+=copyin(finalmsg, handshakeheader, i, handshakeheader.length);
		i+=copyin(finalmsg, encd, i, encd.length);
		i+=copyin(finalmsg, mac, i, mac.length);

		printbytes(5, node, "CLIENTKEYEXCHANGE", finalmsg);

		return finalmsg;
	}

	/**
	 * Interpret the received Finished
	 *
	 * @param bytestr - a bytestr with the Finished msg
	 */
	public void interpretFinished(byte[] bytestr) throws TLSException {
		int i, k;
		byte cryptkey[] = null;
		byte mackey[] = null;
		byte iv[] = null;

		byte md5sum[] = new byte[16];;
		byte sha1sum[] = new byte[20];
		String label = null;
		int recordlength, handshakelength;
		recordlength = 	checkRecordHeader(bytestr, TLS.HANDSHAKE, TLS.VERSIONMAJOR, TLS.VERSIONMINOR);
		handshakelength = checkHandshakeHeader(bytestr, TLS.FINISHED);

		md5sum=sce.getMD5Digest(allhandshakes);
		sha1sum=sce.getSHA1Digest(allhandshakes);

		// which keys do we use?
		if (client) {
			cryptkey = server_write_key;
			mackey = server_mac_key;
			iv = server_write_iv;
			label = "server";
		} else {
			cryptkey = client_write_key;
			mackey = client_mac_key;
			iv = client_write_iv;
			label = "client";
		}

		byte verify[] = new byte[handshakelength];

		// verify array begins 10 bytes in
		for (i=9, k=0;k<handshakelength;k++)
			verify[k] = bytestr[i++];
		printbytes(5, node, "VERIFY_ENCRYPTED", verify);

		byte decrypted[] = tlsciphersuite.decrypt(cryptkey, verify, iv);

		// use this as the IV when next we encrypt
		lastpacket=verify;

		printbytes(5, node, "VERIFY_DECRYPTED", decrypted);

		byte thehash[] = new byte[recordlength-verify.length-4];
		for (k=0;k<thehash.length;k++)
			thehash[k] = bytestr[i++];

		// recalculate our version of the 'verify' array
		byte result[] = sce.PRF(mastersecret, label, sce.bytecat(md5sum, sha1sum));
		printbytes(5, node, "ALLHANDSHAKES1", allhandshakes);

		for (k=0;k<12;k++) {
			if (result[k] != decrypted[k]) {
				throw new TLSException("interpretFinished: verification of the VERIFY array failed");
			}
		}

		// The hash is calculated over the encrypted verify, recreate it and compare..
		byte verifyhash[] = sce.HMacSHA1(verify, mackey);

		// finally, test the mac
		if (thehash.length < verifyhash.length) {
			throw new TLSException("Hash mismatch in finished, Dying...");
		}
		for (k=0;k<thehash.length;k++) {
			if (thehash[k] != verifyhash[k]) {
				throw new TLSException("Hash mismatch in finished, Dying...");
			}
		}
	}

	/**
	 * For a client resuming a session
	 *
	 * @param res - TLSResumeObject containing a master secret/session id pair
 	 */
	public void resumeSession(TLSResumeObject res)
	{
		sessionid = res.sessionid;
		mastersecret=res.mastersecret;
	}

	/**
	 *  Reads in the openssl created certificate
	 */
	public void initCertificate() throws TLSException {
		try {
			InputStream inStream = new FileInputStream("server.pem");
			cert = X509Certificate.getInstance(inStream);
			inStream.close();
		} catch (Exception e) {
			throw new TLSException("Could not open the certificate file: " +e);
		}
	}

	/**
	 * For the server resuming a session
	 *
	 * @param byte[] - byte array containing the master secret
	 */
	public void resumeSession(byte[] ms) {
		mastersecret = ms;
	}

	/**
	 *  Sets the cipher suite to use
	 *
	 * @param CS - the cipher suite
	 */
	public void setCipher(byte CS[]) {
		// CS[0] is always 0x00 for this implementation
		if(CS[1] == TLS_RSA_WITH_RC4_64_SHA[1]) {
			tlsciphersuite = new TLSRC4();
		} else if (CS[1] == TLS_RSA_WITH_DES_CBC_SHA[1]) {
			tlsciphersuite = new TLSDESCBC();
		}
	}

	/**
	 * TLSInteraction constructor
	 *
	 * @param CipherSuite list in preferencial order (a client parameter)
	 * @param isclient - true for yes, false for no
	 * @param node - a node object.. for printing
	 */
	public TLSInteraction(byte ciphersuite[], boolean isclient, Node node) {
		int i = 0;

		sce = new SimnetCryptoEngine ();
		this.node = node;

		int gmt_unix_time = (int)(System.currentTimeMillis()/1000);

		byte thetime[] = convertInt(gmt_unix_time);
		byte randoms[] = new byte[28];
		client = isclient;

		// form the randomness and such..
		if (client) {
			clientrandom = new byte[32];
			sce.getRandomBytes(randoms);

			i+=copyin(clientrandom, thetime, i, thetime.length);
			i+=copyin(clientrandom, randoms, i, randoms.length);
		} else {
			serverrandom = new byte[32];
			sce.getRandomBytes(randoms);

			i+=copyin(serverrandom, thetime, i, thetime.length);
			i+=copyin(serverrandom, randoms, i, randoms.length);
		}

		this.ciphersuite = ciphersuite;
	}

	/**
	 * forms the change cipher spec -- currently a static formation (to be changed)
	 *
	 * @return byte[] - byte array with the changecipherspec msg
	 */
	public byte[] formChangeCipherSpec() {
		// 14 03 01 00 01 01
		/* this is entirely static, it says send a cipher spec message type
		   version 3.1 (represented by 0x03, 0x01)
		   a 2 byte length of what's the follow which is 1 byte so 0x00, 0x01
		   and the value "1" which is constant and the data of the record */
		byte ret[] = { 0x14, 0x03, 0x01, 0x00, 0x01, 0x01 };
		return ret;
	}

	/**
	 * Interpret the received ChangeCipherSpec.  This TLS implementation does not really support ChangeCipherSpec
	 * messages.  This is more of a place holder.
	 *
	 * @param bytestr - a bytestr with the ChangeCipherSpec msg
	 */
	public void interpretChangeCipherSpec(byte[] changecipherspec) throws TLSException {
		byte ret[] = { 0x14, 0x03, 0x01, 0x00, 0x01, 0x01 };
		if (ret.length != changecipherspec.length) {
			throw new TLSException("interpretChangeCipherSpec: Badly formatted ChangeCipherSpec, length mismatch");
		}

		for (int i=0;i<changecipherspec.length;i++)
			if (changecipherspec[i] != ret[i]) {
				// should never ever happen, just cosmetic
				throw new TLSException("interpretChangeCipherSpec: Badly formatted ChangeCipherSpec");
			}
	}

	/**
	 * Checks a handshake header
	 *
	 * @param bytestr[] - the received msg, header and all
	 * @param recordtype - the recordtype it should be
	 * @param versionmajor - most significant byte of the version
	 * @param versionminor - least significant byte of the version
	 * @return int - the expected length of the record
	 */
	public int checkRecordHeader(byte bytestr[], byte recordtype, byte versionmajor, byte versionminor) throws TLSException {
		int ret;
		if (bytestr[0] != recordtype) {
			throw new TLSException("Expected record type of ["+recordtype+"], but got a [" + (byte)bytestr[0] + "], dying..");
		}

		if (bytestr[1] != versionmajor || bytestr[2] != versionminor) {
			throw new TLSException ("Version mismatch!");
		}

		ret = TLS.convertTwoByteLength (bytestr[3], bytestr[4]);
		if (ret < 0) {
			throw new TLSException("Negative length in record header, dying..");
		}

		node.printout(1, node.id, "Expect the record(type: " + recordtype + ") to be [" + ret + "] byte longs");

		return ret;
	}

	/**
	 * Checks the handshake header.
	 *
	 * @param bytestr[] - the received msg, header and all
	 * @param handshaketype - the handshaketype it should be
	 * @return int - the expected length of the handshake message
	 */
	public int checkHandshakeHeader(byte bytestr[], byte handshaketype) throws TLSException {
		int ret;

		if (bytestr[5] != handshaketype) {
			throw new TLSException("Expected a handshake type of [" + handshaketype + "], but received a: " + bytestr[5] + ", dying..");
		}

		ret = TLS.convertThreeByteLength(bytestr[6], bytestr[7], bytestr[8]);
		node.printout(1, node.id, "Expect the handshake(type: " + bytestr[5] + ") to be [" + ret  + "] byte longs");

		if (ret < 0) {
			throw new TLSException("Negative length in record header, dying..");
		}

		return ret;
	}

	/**
	 * Forms the server hello done msg -- static msg (going to change the code, but it'll always be static)
	 *
	 * @return byte[] - byte array with the server hello done msg
	 */
	public byte[] formServerHelloDone() {
		byte ret[] = { 0x16, 0x03, 0x01, 0x00, 0x04, 0x0e, 0x00, 0x00, 0x00 };
		return ret;
	}

	/**
	 * Adds the bytestring to the list of all handshakes for use in finished msgs
	 *
	 * @param addthis - the byte string to add
	 */
	public void addhandshake(byte addthis[]) {
		int i=0, k;
		int newlen = 0;
		byte[] tmphandshakes=null;

		// have to do all this to expand the length of the allhandshakes byte array
		if (allhandshakes == null) {
			allhandshakes = addthis;
			return;
		} else {
			newlen = allhandshakes.length + addthis.length;
			tmphandshakes = new byte[newlen];

			for (i=0;i<allhandshakes.length;i++) {
				tmphandshakes[i] = allhandshakes[i];
			}
		}

		for (k=0;k<addthis.length;k++) {
			tmphandshakes[i++] = addthis[k];
		}

		allhandshakes = tmphandshakes;
	}

	/**
	 * Adds the byte string to the list of handshake msgs but removes the record header first
	 *
	 * @param addthis - a byte array containing what to add
	 */
	public void addhandshaker(byte addthis[]) {
		byte[] tmphandshakes = new byte[addthis.length-5];
		int i, k;

		for (i=5, k=0;i<addthis.length;i++) {
			tmphandshakes[k++] = addthis[i];
		}

		addhandshake (tmphandshakes);
	}

	/**
	 *	Really bad toString method.
	 */
	public String toString() {
		return "TLSInteraction Object";
	}
}
