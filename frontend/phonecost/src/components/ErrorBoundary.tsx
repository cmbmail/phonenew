import React from 'react';
import { Result, Button } from 'antd';
import i18n from 'i18next';

interface Props {
  children: React.ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends React.Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('ErrorBoundary caught:', error, errorInfo);
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 300 }}>
          <Result
            status="error"
            title={i18n.t('errorBoundary.title')}
            subTitle={this.state.error?.message || i18n.t('errorBoundary.unknownError')}
            extra={[
              <Button key="retry" type="primary" onClick={this.handleReset}>
                {i18n.t('errorBoundary.retry')}
              </Button>,
              <Button key="home" onClick={() => { window.location.href = '/'; }}>
                {i18n.t('errorBoundary.backHome')}
              </Button>,
            ]}
          />
        </div>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;
