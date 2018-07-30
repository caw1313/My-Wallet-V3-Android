package piuk.blockchain.android.ui.buysell.coinify.signup.createaccountcompleted

import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.fragment_coinify_create_account_completed.*
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.buysell.coinify.signup.CoinifyFlowListener
import piuk.blockchain.androidcoreui.utils.extensions.inflate

class CoinifyCreateAccountCompletedFragment : Fragment() {

    private var signUpListener: CoinifyFlowListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = container?.inflate(R.layout.fragment_coinify_create_account_completed)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        createAccountCompletedContinueButton.setOnClickListener {
            signUpListener?.requestContinueVerification()
        }
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        if (context is CoinifyFlowListener) {
            signUpListener = context
        } else {
            throw RuntimeException("$context must implement CoinifyFlowListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        signUpListener = null
    }

    companion object {

        fun newInstance(): CoinifyCreateAccountCompletedFragment =
            CoinifyCreateAccountCompletedFragment()
    }
}